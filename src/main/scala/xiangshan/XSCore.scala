/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan

import chipsalliance.rocketchip.config
import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{BundleBridgeSource, LazyModule, LazyModuleImp}
import freechips.rocketchip.interrupts.{IntSinkNode, IntSinkPortSimple}
import freechips.rocketchip.tile.HasFPUParameters
import system.HasSoCParameter
import utils._
import utility._
import xiangshan.backend._
import xiangshan.backend.exu.{ExuConfig, Wb2Ctrl, WbArbiterWrapper}
import xiangshan.frontend._
import xiangshan.mem.L1PrefetchFuzzer

import scala.collection.mutable.ListBuffer

abstract class XSModule(implicit val p: Parameters) extends Module
  with HasXSParameter
  with HasFPUParameters

//remove this trait after impl module logic
trait NeedImpl {
  this: RawModule =>
  protected def IO[T <: Data](iodef: T): T = {
    println(s"[Warn]: (${this.name}) please reomve 'NeedImpl' after implement this module")
    val io = chisel3.experimental.IO(iodef)
    io <> DontCare
    io
  }
}

class WritebackSourceParams(
  var exuConfigs: Seq[Seq[ExuConfig]] = Seq()
 ) {
  def length: Int = exuConfigs.length
  def ++(that: WritebackSourceParams): WritebackSourceParams = {
    new WritebackSourceParams(exuConfigs ++ that.exuConfigs)
  }
}

trait HasWritebackSource {
  val writebackSourceParams: Seq[WritebackSourceParams]
  final def writebackSource(sourceMod: HasWritebackSourceImp): Seq[Seq[Valid[ExuOutput]]] = {
    require(sourceMod.writebackSource.isDefined, "should not use Valid[ExuOutput]")
    val source = sourceMod.writebackSource.get
    require(source.length == writebackSourceParams.length, "length mismatch between sources")
    for ((s, p) <- source.zip(writebackSourceParams)) {
      require(s.length == p.length, "params do not match with the exuOutput")
    }
    source
  }
  final def writebackSource1(sourceMod: HasWritebackSourceImp): Seq[Seq[DecoupledIO[ExuOutput]]] = {
    require(sourceMod.writebackSource1.isDefined, "should not use DecoupledIO[ExuOutput]")
    val source = sourceMod.writebackSource1.get
    require(source.length == writebackSourceParams.length, "length mismatch between sources")
    for ((s, p) <- source.zip(writebackSourceParams)) {
      require(s.length == p.length, "params do not match with the exuOutput")
    }
    source
  }
  val writebackSourceImp: HasWritebackSourceImp
}

trait HasWritebackSourceImp {
  def writebackSource: Option[Seq[Seq[Valid[ExuOutput]]]] = None
  def writebackSource1: Option[Seq[Seq[DecoupledIO[ExuOutput]]]] = None
}

trait HasWritebackSink {
  // Caches all sources. The selected source will be the one with smallest length.
  var writebackSinks = ListBuffer.empty[(Seq[HasWritebackSource], Seq[Int])]
  def addWritebackSink(source: Seq[HasWritebackSource], index: Option[Seq[Int]] = None): HasWritebackSink = {
    val realIndex = if (index.isDefined) index.get else Seq.fill(source.length)(0)
    writebackSinks += ((source, realIndex))
    this
  }

  def writebackSinksParams: Seq[WritebackSourceParams] = {
    writebackSinks.map{ case (s, i) => s.zip(i).map(x => x._1.writebackSourceParams(x._2)).reduce(_ ++ _) }.toSeq
  }
  final def writebackSinksMod(
     thisMod: Option[HasWritebackSource] = None,
     thisModImp: Option[HasWritebackSourceImp] = None
   ): Seq[Seq[HasWritebackSourceImp]] = {
    require(thisMod.isDefined == thisModImp.isDefined)
    writebackSinks.map(_._1.map(source =>
      if (thisMod.isDefined && source == thisMod.get) thisModImp.get else source.writebackSourceImp)
    ).toSeq
  }
  final def writebackSinksImp(
    thisMod: Option[HasWritebackSource] = None,
    thisModImp: Option[HasWritebackSourceImp] = None
  ): Seq[Seq[ValidIO[ExuOutput]]] = {
    val sourceMod = writebackSinksMod(thisMod, thisModImp)
    writebackSinks.zip(sourceMod).map{ case ((s, i), m) =>
      s.zip(i).zip(m).flatMap(x => x._1._1.writebackSource(x._2)(x._1._2))
    }.toSeq
  }
  def selWritebackSinks(func: WritebackSourceParams => Int): Int = {
    writebackSinksParams.zipWithIndex.minBy(params => func(params._1))._2
  }
  def generateWritebackIO(
    thisMod: Option[HasWritebackSource] = None,
    thisModImp: Option[HasWritebackSourceImp] = None
   ): Unit
}

abstract class XSBundle(implicit val p: Parameters) extends Bundle
  with HasXSParameter

abstract class XSCoreBase()(implicit p: config.Parameters) extends LazyModule
  with HasXSParameter with HasExuWbHelper
{
  override def shouldBeInlined: Boolean = false
  // interrupt sinks
  val clint_int_sink = IntSinkNode(IntSinkPortSimple(1, 2))
  val debug_int_sink = IntSinkNode(IntSinkPortSimple(1, 1))
  val plic_int_sink = IntSinkNode(IntSinkPortSimple(2, 1))
  // outer facing nodes
  val frontend = LazyModule(new Frontend())
  val csrOut = BundleBridgeSource(Some(() => new DistributedCSRIO()))

  val wbArbiter = LazyModule(new WbArbiterWrapper(exuConfigs, NRIntWritePorts, NRFpWritePorts))
  val intWbPorts = wbArbiter.intWbPorts
  val fpWbPorts = wbArbiter.fpWbPorts

  // TODO: better RS organization
  // generate rs according to number of function units
  require(exuParameters.JmpCnt == 1)
  require(exuParameters.MduCnt <= exuParameters.AluCnt && exuParameters.MduCnt > 0)
  require(exuParameters.FmiscCnt <= exuParameters.FmacCnt && exuParameters.FmiscCnt > 0)
  require(exuParameters.LduCnt == exuParameters.StuCnt) // TODO: remove this limitation

  // one RS every 2 MDUs
  val schedulePorts = Seq(
    // exuCfg, numDeq, intFastWakeupTarget, fpFastWakeupTarget
    Seq(
      (AluExeUnitCfg, exuParameters.AluCnt, Seq(AluExeUnitCfg, LdExeUnitCfg, StaExeUnitCfg), Seq()),
      (MulDivExeUnitCfg, exuParameters.MduCnt, Seq(AluExeUnitCfg, MulDivExeUnitCfg), Seq()),
      (JumpCSRExeUnitCfg, 1, Seq(), Seq()),
      (LdExeUnitCfg, exuParameters.LduCnt, Seq(AluExeUnitCfg, LdExeUnitCfg), Seq()),
      (StaExeUnitCfg, exuParameters.StuCnt, Seq(), Seq()),
      (StdExeUnitCfg, exuParameters.StuCnt, Seq(), Seq())
    ),
    Seq(
      (FmacExeUnitCfg, exuParameters.FmacCnt, Seq(), Seq(FmacExeUnitCfg, FmiscExeUnitCfg)),
      (FmiscExeUnitCfg, exuParameters.FmiscCnt, Seq(), Seq())
    )
  )

  // should do outer fast wakeup ports here
  val otherFastPorts = schedulePorts.zipWithIndex.map { case (sche, i) =>
    val otherCfg = schedulePorts.zipWithIndex.filter(_._2 != i).map(_._1).reduce(_ ++ _)
    val outerPorts = sche.map(cfg => {
      // exe units from this scheduler need fastUops from exeunits
      val outerWakeupInSche = sche.filter(_._1.wakeupFromExu)
      val intraIntScheOuter = outerWakeupInSche.filter(_._3.contains(cfg._1)).map(_._1)
      val intraFpScheOuter = outerWakeupInSche.filter(_._4.contains(cfg._1)).map(_._1)
      // exe units from other schedulers need fastUop from outside
      val otherIntSource = otherCfg.filter(_._3.contains(cfg._1)).map(_._1)
      val otherFpSource = otherCfg.filter(_._4.contains(cfg._1)).map(_._1)
      val intSource = findInWbPorts(intWbPorts, intraIntScheOuter ++ otherIntSource)
      val fpSource = findInWbPorts(fpWbPorts, intraFpScheOuter ++ otherFpSource)
      getFastWakeupIndex(cfg._1, intSource, fpSource, intWbPorts.length).sorted
    })
    println(s"inter-scheduler wakeup sources for $i: $outerPorts")
    outerPorts
  }

  // allow mdu and fmisc to have 2*numDeq enqueue ports
  val intDpPorts = (0 until exuParameters.AluCnt).map(i => {
    if (i < exuParameters.JmpCnt) Seq((0, i), (1, i), (2, i))
    else if (i < 2 * exuParameters.MduCnt) Seq((0, i), (1, i))
    else Seq((0, i))
  })
  val lsDpPorts = (0 until exuParameters.LduCnt).map(i => Seq((3, i))) ++
                  (0 until exuParameters.StuCnt).map(i => Seq((4, i))) ++
                  (0 until exuParameters.StuCnt).map(i => Seq((5, i)))
  val fpDpPorts = (0 until exuParameters.FmacCnt).map(i => {
    if (i < 2 * exuParameters.FmiscCnt) Seq((0, i), (1, i))
    else Seq((0, i))
  })

  val dispatchPorts = Seq(intDpPorts ++ lsDpPorts, fpDpPorts)

  val outIntRfReadPorts = Seq(0, 0)
  val outFpRfReadPorts = Seq(0, StorePipelineWidth)
  val hasIntRf = Seq(true, false)
  val hasFpRf = Seq(false, true)
  val exuBlocks = schedulePorts.zip(dispatchPorts).zip(otherFastPorts).zipWithIndex.map {
    case (((sche, disp), other), i) =>
      LazyModule(new ExuBlock(sche, disp, intWbPorts, fpWbPorts, other, outIntRfReadPorts(i), outFpRfReadPorts(i), hasIntRf(i), hasFpRf(i)))
  }

  val memBlock = LazyModule(new MemBlock()(p.alter((site, here, up) => {
    case XSCoreParamsKey => up(XSCoreParamsKey).copy(
      IssQueSize = exuBlocks.head.scheduler.getMemRsEntries
    )
  })))

  val wb2Ctrl = LazyModule(new Wb2Ctrl(exuConfigs))
  wb2Ctrl.addWritebackSink(exuBlocks :+ memBlock)
  val dpExuConfigs = exuBlocks.flatMap(_.scheduler.dispatch2.map(_.configs))
  val ctrlBlock = LazyModule(new CtrlBlock(dpExuConfigs))
  val writebackSources = Seq(Seq(wb2Ctrl), Seq(wbArbiter))
  writebackSources.foreach(s => ctrlBlock.addWritebackSink(s))
}

class XSCore()(implicit p: config.Parameters) extends XSCoreBase
  with HasXSDts
{
  lazy val module = new XSCoreImp(this)
}

class XSCoreImp(outer: XSCoreBase) extends LazyModuleImp(outer)
  with HasXSParameter
  with HasSoCParameter {
  val io = IO(new Bundle {
    val hartId = Input(UInt(64.W))
    val reset_vector = Input(UInt(PAddrBits.W))
    val cpu_halt = Output(Bool())
    val l2_pf_enable = Output(Bool())
    val perfEvents = Input(Vec(numPCntHc * coreParams.L2NBanks, new PerfEvent))
    val beu_errors = Output(new XSL1BusErrors())
    val l2_hint = Input(Valid(new L2ToL1Hint()))
    val l2PfqBusy = Input(Bool())
    val debugTopDown = new Bundle {
      val robHeadPaddr = Valid(UInt(PAddrBits.W))
      val l2MissMatch = Input(Bool())
      val l3MissMatch = Input(Bool())
    }
  })

  println(s"FPGAPlatform:${env.FPGAPlatform} EnableDebug:${env.EnableDebug}")

  private val frontend = outer.frontend.module
  private val ctrlBlock = outer.ctrlBlock.module
  private val wb2Ctrl = outer.wb2Ctrl.module
  private val memBlock = outer.memBlock.module
  private val exuBlocks = outer.exuBlocks.map(_.module)

  frontend.io.hartId  := io.hartId
  ctrlBlock.io.hartId := io.hartId
  exuBlocks.foreach(_.io.hartId := io.hartId)
  memBlock.io.hartId := io.hartId
  outer.wbArbiter.module.io.hartId := io.hartId
  frontend.io.reset_vector := io.reset_vector

  io.cpu_halt := ctrlBlock.io.cpu_halt

  outer.wbArbiter.module.io.redirect <> ctrlBlock.io.redirect
  val allWriteback = exuBlocks.flatMap(_.io.fuWriteback) ++ memBlock.io.mem_to_ooo.writeback
  require(exuConfigs.length == allWriteback.length, s"${exuConfigs.length} != ${allWriteback.length}")
  outer.wbArbiter.module.io.in <> allWriteback
  val rfWriteback = outer.wbArbiter.module.io.out

  // memblock error exception writeback, 1 cycle after normal writeback
  wb2Ctrl.io.s3_delayed_load_error <> memBlock.io.s3_delayed_load_error

  wb2Ctrl.io.redirect <> ctrlBlock.io.redirect
  outer.wb2Ctrl.generateWritebackIO()

  io.beu_errors.icache <> frontend.io.error.toL1BusErrorUnitInfo()
  io.beu_errors.dcache <> memBlock.io.error.toL1BusErrorUnitInfo()
  io.beu_errors.l2 <> DontCare

  require(exuBlocks.count(_.fuConfigs.map(_._1).contains(JumpCSRExeUnitCfg)) == 1)
  val csrFenceMod = exuBlocks.filter(_.fuConfigs.map(_._1).contains(JumpCSRExeUnitCfg)).head
  val csrioIn = csrFenceMod.io.fuExtra.csrio.get
  val fenceio = csrFenceMod.io.fuExtra.fenceio.get

  frontend.io.backend <> ctrlBlock.io.frontend
  frontend.io.sfence <> fenceio.sfence
  frontend.io.tlbCsr <> csrioIn.tlb
  frontend.io.csrCtrl <> csrioIn.customCtrl
  frontend.io.fencei := fenceio.fencei

  ctrlBlock.io.csrCtrl <> csrioIn.customCtrl
  val redirectBlocks = exuBlocks.reverse.filter(_.fuConfigs.map(_._1).map(_.hasRedirect).reduce(_ || _))
  ctrlBlock.io.exuRedirect <> redirectBlocks.flatMap(_.io.fuExtra.exuRedirect)
  ctrlBlock.io.stIn <> memBlock.io.mem_to_ooo.stIn
  ctrlBlock.io.memoryViolation <> memBlock.io.mem_to_ooo.memoryViolation
  exuBlocks.head.io.scheExtra.enqLsq.get <> memBlock.io.ooo_to_mem.enqLsq
  exuBlocks.foreach(b => {
    b.io.scheExtra.lcommit := memBlock.io.mem_to_ooo.lqDeq
    b.io.scheExtra.scommit := memBlock.io.mem_to_ooo.sqDeq
    b.io.scheExtra.lqCancelCnt := memBlock.io.mem_to_ooo.lqCancelCnt
    b.io.scheExtra.sqCancelCnt := memBlock.io.mem_to_ooo.sqCancelCnt
  })
  val sourceModules = outer.writebackSources.map(_.map(_.module.asInstanceOf[HasWritebackSourceImp]))
  outer.ctrlBlock.generateWritebackIO()

  val allFastUop = exuBlocks.flatMap(b => b.io.fastUopOut.dropRight(b.numOutFu)) ++ memBlock.io.mem_to_ooo.otherFastWakeup
  require(allFastUop.length == exuConfigs.length, s"${allFastUop.length} != ${exuConfigs.length}")
  val intFastUop = allFastUop.zip(exuConfigs).filter(_._2.writeIntRf).map(_._1)
  val fpFastUop = allFastUop.zip(exuConfigs).filter(_._2.writeFpRf).map(_._1)
  val intFastUop1 = outer.wbArbiter.intConnections.map(c => intFastUop(c.head))
  val fpFastUop1 = outer.wbArbiter.fpConnections.map(c => fpFastUop(c.head))
  val allFastUop1 = intFastUop1 ++ fpFastUop1

  ctrlBlock.io.dispatch <> exuBlocks.flatMap(_.io.in)
  ctrlBlock.io.rsReady := exuBlocks.flatMap(_.io.scheExtra.rsReady)
  ctrlBlock.io.enqLsq <> memBlock.io.ooo_to_mem.enqLsq
  ctrlBlock.io.lqDeq := memBlock.io.mem_to_ooo.lqDeq
  ctrlBlock.io.sqDeq := memBlock.io.mem_to_ooo.sqDeq
  ctrlBlock.io.lqCanAccept := memBlock.io.mem_to_ooo.lsqio.lqCanAccept
  ctrlBlock.io.sqCanAccept := memBlock.io.mem_to_ooo.lsqio.sqCanAccept
  ctrlBlock.io.lqCancelCnt := memBlock.io.mem_to_ooo.lqCancelCnt
  ctrlBlock.io.sqCancelCnt := memBlock.io.mem_to_ooo.sqCancelCnt
  ctrlBlock.io.robHeadLsIssue := exuBlocks.map(_.io.scheExtra.robHeadLsIssue).reduce(_ || _)

  exuBlocks(0).io.scheExtra.fpRfReadIn.get <> exuBlocks(1).io.scheExtra.fpRfReadOut.get
  exuBlocks(0).io.scheExtra.fpStateReadIn.get <> exuBlocks(1).io.scheExtra.fpStateReadOut.get

  for((c, e) <- ctrlBlock.io.ld_pc_read.zip(exuBlocks(0).io.issue.get)){
    // read load pc at load s0
    c.ptr := e.bits.uop.cf.ftqPtr
    c.offset := e.bits.uop.cf.ftqOffset
  }
  // return load pc at load s2
  memBlock.io.ooo_to_mem.loadPc <> VecInit(ctrlBlock.io.ld_pc_read.map(_.data))

  for((c, e) <- ctrlBlock.io.st_pc_read.zip(exuBlocks(0).io.issue.get.drop(exuParameters.LduCnt))){
    // read store pc at store s0
    c.ptr := e.bits.uop.cf.ftqPtr
    c.offset := e.bits.uop.cf.ftqOffset
  }
  // return store pc at store s2
  memBlock.io.ooo_to_mem.storePc <> VecInit(ctrlBlock.io.st_pc_read.map(_.data))

  memBlock.io.ooo_to_mem.issue <> exuBlocks(0).io.issue.get
  // By default, instructions do not have exceptions when they enter the function units.
  memBlock.io.ooo_to_mem.issue.map(_.bits.uop.clearExceptions())
  exuBlocks(0).io.scheExtra.loadFastMatch.get <> memBlock.io.ooo_to_mem.loadFastMatch
  exuBlocks(0).io.scheExtra.loadFastFuOpType.get <> memBlock.io.ooo_to_mem.loadFastFuOpType
  exuBlocks(0).io.scheExtra.loadFastImm.get <> memBlock.io.ooo_to_mem.loadFastImm

  val stdIssue = exuBlocks(0).io.issue.get.takeRight(exuParameters.StuCnt)
  exuBlocks.map(_.io).foreach { exu =>
    exu.redirect <> ctrlBlock.io.redirect
    exu.allocPregs <> ctrlBlock.io.allocPregs
    exu.rfWriteback <> rfWriteback
    exu.fastUopIn <> allFastUop1
    exu.scheExtra.jumpPc <> ctrlBlock.io.jumpPc
    exu.scheExtra.jalr_target <> ctrlBlock.io.jalr_target
    exu.scheExtra.stIssuePtr <> memBlock.io.mem_to_ooo.stIssuePtr
    exu.scheExtra.debug_fp_rat <> ctrlBlock.io.debug_fp_rat
    exu.scheExtra.debug_int_rat <> ctrlBlock.io.debug_int_rat
    exu.scheExtra.robDeqPtr := ctrlBlock.io.robDeqPtr
    exu.scheExtra.memWaitUpdateReq.staIssue.zip(memBlock.io.mem_to_ooo.stIn).foreach{case (sink, src) => {
      sink.bits := src.bits
      sink.valid := src.valid
    }}
    exu.scheExtra.memWaitUpdateReq.stdIssue.zip(stdIssue).foreach{case (sink, src) => {
      sink.valid := src.valid
      sink.bits := src.bits
    }}
  }
  XSPerfHistogram("fastIn_count", PopCount(allFastUop1.map(_.valid)), true.B, 0, allFastUop1.length, 1)
  XSPerfHistogram("wakeup_count", PopCount(rfWriteback.map(_.valid)), true.B, 0, rfWriteback.length, 1)

  ctrlBlock.perfinfo.perfEventsEu0 := exuBlocks(0).getPerf.dropRight(outer.exuBlocks(0).scheduler.numRs)
  ctrlBlock.perfinfo.perfEventsEu1 := exuBlocks(1).getPerf.dropRight(outer.exuBlocks(1).scheduler.numRs)
  ctrlBlock.perfinfo.perfEventsRs  := outer.exuBlocks.flatMap(b => b.module.getPerf.takeRight(b.scheduler.numRs))

  csrioIn.hartId <> io.hartId
  csrioIn.perf <> DontCare
  csrioIn.perf.retiredInstr <> ctrlBlock.io.robio.toCSR.perfinfo.retiredInstr
  csrioIn.perf.ctrlInfo <> ctrlBlock.io.perfInfo.ctrlInfo
  csrioIn.perf.memInfo <> memBlock.io.memInfo
  csrioIn.perf.frontendInfo <> frontend.io.frontendInfo

  csrioIn.perf.perfEventsFrontend <> frontend.getPerf
  csrioIn.perf.perfEventsCtrl     <> ctrlBlock.getPerf
  csrioIn.perf.perfEventsLsu      <> memBlock.getPerf
  csrioIn.perf.perfEventsHc       <> io.perfEvents

  csrioIn.fpu.fflags <> ctrlBlock.io.robio.toCSR.fflags
  csrioIn.fpu.isIllegal := false.B
  csrioIn.fpu.dirty_fs <> ctrlBlock.io.robio.toCSR.dirty_fs
  csrioIn.fpu.frm <> exuBlocks(1).io.fuExtra.frm.get
  csrioIn.exception <> ctrlBlock.io.robio.exception
  csrioIn.isXRet <> ctrlBlock.io.robio.toCSR.isXRet
  csrioIn.trapTarget <> ctrlBlock.io.robio.toCSR.trapTarget
  csrioIn.interrupt <> ctrlBlock.io.robio.toCSR.intrBitSet
  csrioIn.wfi_event <> ctrlBlock.io.robio.toCSR.wfiEvent
  csrioIn.memExceptionVAddr <> memBlock.io.mem_to_ooo.lsqio.vaddr

  csrioIn.externalInterrupt.msip := outer.clint_int_sink.in.head._1(0)
  csrioIn.externalInterrupt.mtip := outer.clint_int_sink.in.head._1(1)
  csrioIn.externalInterrupt.meip := outer.plic_int_sink.in.head._1(0)
  csrioIn.externalInterrupt.seip := outer.plic_int_sink.in.last._1(0)
  csrioIn.externalInterrupt.debug := outer.debug_int_sink.in.head._1(0)

  csrioIn.distributedUpdate(0).w.valid := memBlock.io.mem_to_ooo.csrUpdate.w.valid
  csrioIn.distributedUpdate(0).w.bits := memBlock.io.mem_to_ooo.csrUpdate.w.bits
  csrioIn.distributedUpdate(1).w.valid := frontend.io.csrUpdate.w.valid
  csrioIn.distributedUpdate(1).w.bits := frontend.io.csrUpdate.w.bits

  fenceio.sfence <> memBlock.io.ooo_to_mem.sfence
  memBlock.io.fetch_to_mem.itlb <> frontend.io.ptw
  memBlock.io.ooo_to_mem.flushSb := fenceio.sbuffer.flushSb
  fenceio.sbuffer.sbIsEmpty := memBlock.io.mem_to_ooo.sbIsEmpty


  memBlock.io.redirect <> ctrlBlock.io.redirect
  memBlock.io.rsfeedback <> exuBlocks(0).io.scheExtra.feedback.get

  memBlock.io.ooo_to_mem.csrCtrl <> csrioIn.customCtrl
  memBlock.io.ooo_to_mem.tlbCsr <> csrioIn.tlb

  memBlock.io.ooo_to_mem.lsqio.lcommit   := ctrlBlock.io.robio.lsq.lcommit
  memBlock.io.ooo_to_mem.lsqio.scommit   := ctrlBlock.io.robio.lsq.scommit
  memBlock.io.ooo_to_mem.lsqio.pendingld := ctrlBlock.io.robio.lsq.pendingld
  memBlock.io.ooo_to_mem.lsqio.pendingst := ctrlBlock.io.robio.lsq.pendingst
  memBlock.io.ooo_to_mem.lsqio.commit    := ctrlBlock.io.robio.lsq.commit
  memBlock.io.ooo_to_mem.lsqio.pendingPtr:= ctrlBlock.io.robio.lsq.pendingPtr
  ctrlBlock.io.robio.lsq.mmio            := memBlock.io.mem_to_ooo.lsqio.mmio
  ctrlBlock.io.robio.lsq.uop             := memBlock.io.mem_to_ooo.lsqio.uop
//  memBlock.io.lsqio.rob <> ctrlBlock.io.robio.lsq
  memBlock.io.ooo_to_mem.isStore := CommitType.lsInstIsStore(ctrlBlock.io.robio.exception.bits.uop.ctrl.commitType)
  memBlock.io.debug_ls <> ctrlBlock.io.robio.debug_ls
  memBlock.io.mem_to_ooo.lsTopdownInfo <> ctrlBlock.io.robio.lsTopdownInfo
  memBlock.io.l2_hint.valid := io.l2_hint.valid
  memBlock.io.l2_hint.bits.sourceId := io.l2_hint.bits.sourceId
  memBlock.io.l2PfqBusy := io.l2PfqBusy
  memBlock.io.int2vlsu <> DontCare
  memBlock.io.vec2vlsu <> DontCare
  memBlock.io.vlsu2vec <> DontCare
  memBlock.io.vlsu2int <> DontCare
  memBlock.io.vlsu2ctrl <> DontCare

  // if l2 prefetcher use stream prefetch, it should be placed in XSCore
  io.l2_pf_enable := csrioIn.customCtrl.l2_pf_enable

  // top-down info
  memBlock.io.debugTopDown.robHeadVaddr := ctrlBlock.io.debugTopDown.fromRob.robHeadVaddr
  frontend.io.debugTopDown.robHeadVaddr := ctrlBlock.io.debugTopDown.fromRob.robHeadVaddr
  io.debugTopDown.robHeadPaddr := ctrlBlock.io.debugTopDown.fromRob.robHeadPaddr
  ctrlBlock.io.debugTopDown.fromCore.l2MissMatch := io.debugTopDown.l2MissMatch
  ctrlBlock.io.debugTopDown.fromCore.l3MissMatch := io.debugTopDown.l3MissMatch
  ctrlBlock.io.debugTopDown.fromCore.fromMem := memBlock.io.debugTopDown.toCore
  memBlock.io.debugRolling := ctrlBlock.io.debugRolling

  // Modules are reset one by one
  val resetTree = ResetGenNode(
    Seq(
      ModuleNode(memBlock),
      ResetGenNode(Seq(
        ModuleNode(exuBlocks.head),
        ResetGenNode(
          exuBlocks.tail.map(m => ModuleNode(m)) :+ ModuleNode(outer.wbArbiter.module)
        ),
        ResetGenNode(Seq(
          ModuleNode(ctrlBlock),
          ResetGenNode(Seq(
            ModuleNode(frontend)
          ))
        ))
      ))
    )
  )

  ResetGen(resetTree, reset, !debugOpts.FPGAPlatform)

}
