package xiangshan.cache

import chisel3._
import chisel3.util._

import freechips.rocketchip.tilelink.{ClientMetadata, ClientStates, TLPermissions}

import utils.{XSDebug, OneHot}

class MainPipeReq extends DCacheBundle
{
  // for request that comes from MissQueue
  // does this req come from MissQueue
  val miss = Bool()
  // which MissQueueEntry send this req?
  val miss_id = UInt(log2Up(cfg.nMissEntries).W)
  // what permission are we granted with?
  val miss_param = UInt(TLPermissions.bdWidth.W)

  // for request that comes from MissQueue
  // does this req come from Probe
  val probe = Bool()
  val probe_param = UInt(TLPermissions.bdWidth.W)

  // request info
  // reqs from MissQueue, Store, AMO use this
  // probe does not use this
  val source = UInt(sourceTypeWidth.W)
  val cmd    = UInt(M_SZ.W)
  // must be aligned to block
  val addr   = UInt(PAddrBits.W)

  // store
  val store_data   = UInt((cfg.blockBytes * 8).W)
  val store_mask   = UInt(cfg.blockBytes.W)

  // which word does amo work on?
  val word_idx = UInt(log2Up(cfg.blockBytes * 8 / DataBits).W)
  val amo_data   = UInt(DataBits.W)
  val amo_mask   = UInt((DataBits/8).W)

  val id     = UInt(reqIdWidth.W)

  def dump() = {
    XSDebug("MainPipeReq: miss: %b miss_id: %d miss_param: %d probe: %b probe_param: %d source: %d cmd: %d addr: %x store_data: %x store_mask: %x word_idx: %d data: %x mask: %x id: %d\n",
      miss, miss_id, miss_param, probe, probe_param, source, cmd, addr, store_data, store_mask, word_idx, amo_data, amo_mask, id)
  }
}

class MainPipeResp extends DCacheBundle
{
  val id     = UInt(reqIdWidth.W)
  // AMO resp data
  val data   = UInt(DataBits.W)
  val miss   = Bool()
  val replay = Bool()
  def dump() = {
    XSDebug("MainPipeResp: id: %d data: %x miss: %b replay: %b\n",
      id, data, miss, replay)
  }
}

class MainPipe extends DCacheModule
{
  val io = IO(new DCacheBundle {
    // req and resp
    val req        = Flipped(DecoupledIO(new MainPipeReq))
    val miss_req   = DecoupledIO(new MissReq)
    val miss_resp  = ValidIO(new MainPipeResp)
    val store_resp = ValidIO(new MainPipeResp)
    val amo_resp   = ValidIO(new MainPipeResp)

    // meta/data read/write
    val data_read  = DecoupledIO(new L1DataReadReq)
    val data_resp  = Input(Vec(nWays, Vec(blockRows, Bits(encRowBits.W))))
    val data_write = DecoupledIO(new L1DataWriteReq)

    val meta_read  = DecoupledIO(new L1MetaReadReq)
    val meta_resp  = Input(Vec(nWays, new L1Metadata))
    val meta_write = DecoupledIO(new L1MetaWriteReq)

    // write back
    val wb_req     = DecoupledIO(new WritebackReq)

    // lrsc locked block should block probe
    val lrsc_locked_block = Output(Valid(UInt(PAddrBits.W)))
  })

  // assign default value to output signals
  io.req.ready        := false.B
  io.miss_resp.valid  := false.B
  io.store_resp.valid := false.B
  io.amo_resp.valid   := false.B

  io.data_read.valid  := false.B
  io.data_write.valid := false.B
  io.data_write.bits  := DontCare
  io.meta_read.valid  := false.B
  io.meta_write.valid := false.B
  io.meta_write.bits  := DontCare

  io.wb_req.valid     := false.B
  io.wb_req.bits      := DontCare

  io.lrsc_locked_block.valid := false.B
  io.lrsc_locked_block.bits  := DontCare

  // Pipeline
  // TODO: add full bypass for meta and data, bypass should be based on block address match
  val stall = Wire(Bool())

  // --------------------------------------------------------------------------------
  // stage 0
  // read meta and data

  // valid: this pipeline has valid req
  // fire: req fired and will appear in next pipeline stage
  val s0_valid = io.req.valid
  val s0_fire = io.req.fire()
  val s0_req = io.req.bits

  val word_mask = Wire(Vec(blockRows, Vec(rowWords, Bits(wordBytes.W))))
  for (i <- 0 until blockRows) {
    for (w <- 0 until rowWords) {
      word_mask(i)(w) := s0_req.store_mask((i + 1) * rowBytes - 1, i * rowBytes)((w + 1) * wordBytes - 1, w * wordBytes)
    }
  }

  val word_full_overwrite = Wire(Vec(blockRows, Bits(rowWords.W)))
  val word_write = Wire(Vec(blockRows, Bits(rowWords.W)))
  for (i <- 0 until blockRows) {
    word_full_overwrite(i) := VecInit((0 until rowWords) map { w => word_mask(i)(w).andR }).asUInt
    word_write(i) := VecInit((0 until rowWords) map { w => word_mask(i)(w).orR }).asUInt
  }
  val row_full_overwrite = VecInit(word_full_overwrite.map(w => w.andR)).asUInt
  val row_write = VecInit(word_write.map(w => w.orR)).asUInt
  val full_overwrite = row_full_overwrite.andR

  // If req comes form MissQueue, it must be a full overwrite,
  //   but we still need to read data array
  //   since we may do replacement
  // If it's a store(not from MissQueue):
  //   If it's full mask, no need to read data array;
  //   If it's partial mask, no need to read full masked words.
  // If it's a AMO(not from MissQueue), only need to read the specific word.
  // If it's probe, read it all.

  // do not left out !s0_req.probe,
  // if it's a probe, all data mask fields are useless
  // don't worry about duplicate conditions
  // backend tools will remove them
  val miss_need_data  = s0_req.miss
  val store_need_data = !s0_req.miss && !s0_req.probe && s0_req.source === STORE_SOURCE.U && !full_overwrite
  val amo_need_data = !s0_req.miss && !s0_req.probe && s0_req.source === AMO_SOURCE.U
  val probe_need_data = s0_req.probe

  val need_data = miss_need_data || store_need_data || amo_need_data || probe_need_data

  val meta_read = io.meta_read.bits
  val data_read = io.data_read.bits

  val s1_s0_set_conflict = Wire(Bool())
  val s2_s0_set_conflict = Wire(Bool())
  val set_conflict =  s1_s0_set_conflict || s2_s0_set_conflict

  // sanity check
  when (s0_fire) {
    when (s0_req.miss) {
      assert (full_overwrite)
    }
    // AMO not yet finished
    assert (s0_req.source =/= AMO_SOURCE.U)
    OneHot.checkOneHot(Seq(s0_req.miss, s0_req.probe))
  }

  val meta_ready = io.meta_read.ready
  val data_ready = !need_data || io.data_read.ready
  io.req.ready := meta_ready && data_ready && !set_conflict && !stall

  io.meta_read.valid := io.req.valid && !set_conflict && !stall
  io.data_read.valid := io.req.valid && need_data && !set_conflict && !stall

  // Tag read for new requests
  meta_read.idx    := get_idx(s0_req.addr)
  meta_read.way_en := ~0.U(nWays.W)
  meta_read.tag    := DontCare

  // Data read for new requests
  val rowWordBits = log2Floor(rowWords)
  val amo_row  = s0_req.word_idx >> rowWordBits
  val amo_word = if (rowWordBits == 0) 0.U else s0_req.word_idx(rowWordBits - 1, 0)

  val store_rmask = row_write & ~row_full_overwrite
  val amo_rmask   = UIntToOH(amo_row)
  val full_rmask  = ~0.U(blockRows.W)
  val none_rmask  = 0.U(blockRows.W)

  val rmask = Mux(store_need_data, store_rmask,
    Mux(amo_need_data, amo_rmask,
      Mux(probe_need_data || miss_need_data, full_rmask, none_rmask)))

  // generate wmask here and use it in stage 2
  val store_wmask = word_write
  val amo_wmask   = WireInit(VecInit((0 until blockRows) map (i => 0.U(rowWords.W))))
  amo_wmask(amo_row) := VecInit((0 until rowWords) map (w => w.U === amo_word)).asUInt
  val full_wmask  = VecInit((0 until blockRows) map (i => ~0.U(rowWords.W)))
  val none_wmask  = VecInit((0 until blockRows) map (i => 0.U(rowWords.W)))

  data_read.addr   := s0_req.addr
  data_read.way_en := ~0.U(nWays.W)

  data_read.rmask  := rmask

  dump_pipeline_reqs("MainPipe s0", s0_valid, s0_req)


  // --------------------------------------------------------------------------------
  // stage 1
  // read out meta, check hit or miss
  // TODO: add stalling

  val s1_valid = RegInit(false.B)
  val s1_fire  = s1_valid && !stall
  val s1_req = RegEnable(s0_req, s0_fire)

  val s1_rmask       = RegEnable(rmask, s0_fire)
  val s1_store_wmask = RegEnable(store_wmask, s0_fire)
  val s1_amo_wmask   = RegEnable(amo_wmask, s0_fire)
  val s1_full_wmask  = RegEnable(full_wmask, s0_fire)
  val s1_none_wmask  = RegEnable(none_wmask, s0_fire)

  s1_s0_set_conflict := s1_valid && get_idx(s1_req.addr) === get_idx(s0_req.addr)

  when (s0_fire) { s1_valid := true.B }
  when (!s0_fire && s1_fire) { s1_valid := false.B }

  dump_pipeline_reqs("MainPipe s1", s1_valid, s1_req)

  val meta_resp_latched = Reg(Vec(nWays, new L1Metadata))
  val meta_resp = Mux(RegNext(next = stall, init = false.B), meta_resp_latched, io.meta_resp)
  when (stall) {
    meta_resp_latched := meta_resp
  }

  // tag check
  def wayMap[T <: Data](f: Int => T) = VecInit((0 until nWays).map(f))
  val s1_tag_eq_way = wayMap((w: Int) => meta_resp(w).tag === (get_tag(s1_req.addr))).asUInt
  val s1_tag_match_way = wayMap((w: Int) => s1_tag_eq_way(w) && meta_resp(w).coh.isValid()).asUInt
  val s1_tag_match = s1_tag_match_way.orR

  val s1_fake_meta = Wire(new L1Metadata)
  s1_fake_meta.tag := get_tag(s1_req.addr)
  s1_fake_meta.coh := ClientMetadata.onReset

  // when there are no tag match, we give it a Fake Meta
  // this simplifies our logic in s2 stage
  val s1_hit_meta  = Mux(s1_tag_match, Mux1H(s1_tag_match_way, wayMap((w: Int) => meta_resp(w))), s1_fake_meta)
  val s1_hit_coh = s1_hit_meta.coh

  // replacement policy
  val replacer = cacheParams.replacement
  val s1_repl_way_en = UIntToOH(replacer.way)
  val s1_repl_meta = Mux1H(s1_repl_way_en, wayMap((w: Int) => meta_resp(w)))
  val s1_repl_coh = s1_repl_meta.coh

  // only true miss request(not permission miss) need to do replacement
  // we use repl meta when we really need to a replacement
  val s1_need_replacement = s1_req.miss && !s1_tag_match
  val s1_way_en        = Mux(s1_need_replacement, s1_repl_way_en, s1_tag_match_way)
  val s1_meta          = Mux(s1_need_replacement, s1_repl_meta,   s1_hit_meta)
  val s1_coh           = Mux(s1_need_replacement, s1_repl_coh,  s1_hit_coh)

  // for now, since we are using random replacement
  // we only need to update replacement states after every valid replacement decision
  // we only do replacement when we are true miss(not permission miss)
  when (s1_fire) {
    when (s1_need_replacement) {
      replacer.miss
    }
  }

  // s1 data
  val s1_data_resp_latched = Reg(Vec(nWays, Vec(blockRows, Bits(encRowBits.W))))
  val s1_data_resp = Mux(RegNext(next = stall, init = false.B), s1_data_resp_latched, io.data_resp)
  when (stall) {
    s1_data_resp_latched := s1_data_resp
  }

  // --------------------------------------------------------------------------------
  // stage 2
  // check permissions
  // read out data, do write/amo stuff
  val s2_valid = RegInit(false.B)
  val s2_fire  = s2_valid && !stall
  val s2_req = RegEnable(s1_req, s1_fire)

  val s2_rmask       = RegEnable(s1_rmask, s1_fire)
  val s2_store_wmask = RegEnable(s1_store_wmask, s1_fire)
  val s2_amo_wmask   = RegEnable(s1_amo_wmask, s1_fire)
  val s2_full_wmask  = RegEnable(s1_full_wmask, s1_fire)
  val s2_none_wmask  = RegEnable(s1_none_wmask, s1_fire)

  s2_s0_set_conflict := s2_valid && get_idx(s2_req.addr) === get_idx(s0_req.addr)

  when (s1_fire) { s2_valid := true.B }
  when (!s1_fire && s2_fire) { s2_valid := false.B }

  dump_pipeline_reqs("MainPipe s2", s2_valid, s2_req)

  val s2_tag_match_way  = RegEnable(s1_tag_match_way, s1_fire)
  val s2_tag_match      = RegEnable(s1_tag_match, s1_fire)

  val s2_hit_meta       = RegEnable(s1_hit_meta, s1_fire)
  val s2_hit_coh        = RegEnable(s1_hit_coh, s1_fire)
  val s2_has_permission = s2_hit_coh.onAccess(s2_req.cmd)._1
  val s2_new_hit_coh    = s2_hit_coh.onAccess(s2_req.cmd)._3

  val s2_repl_meta     = RegEnable(s1_repl_meta, s1_fire)
  val s2_repl_coh      = RegEnable(s1_repl_coh, s1_fire)
  val s2_repl_way_en   = RegEnable(s1_repl_way_en, s1_fire)

  // only true miss request(not permission miss) need to do replacement
  // we use repl meta when we really need to a replacement
  val s2_need_replacement = RegEnable(s1_need_replacement, s1_fire)
  val s2_way_en           = RegEnable(s1_way_en, s1_fire)
  val s2_meta             = RegEnable(s1_meta, s1_fire)
  val s2_coh              = RegEnable(s1_coh, s1_fire)
  val s2_data_resp        = RegEnable(s1_data_resp, s1_fire)

  // --------------------------------------------------------------------------------
  // Permission checking
  val miss_new_coh = s2_coh.onGrant(s2_req.cmd, s2_req.miss_param)
  when (s2_valid) {
    // permission checking for miss refill
    when (s2_req.miss) {
      // if miss refill req hits in dcache
      // make sure it has enough permission to complete this cmd
      assert (miss_new_coh.isValid())

      when (s2_tag_match) {
        // if miss refill req hits in dcache
        // then the old permission should be lower than new permission
        // otherwise we would not miss
        assert (s2_hit_coh.state < miss_new_coh.state)
      }
    }
  }

  // Determine what state to go to based on Probe param
  val (probe_has_dirty_data, probe_shrink_param, probe_new_coh) = s2_coh.onProbe(s2_req.probe_param)

  // as long as we has permission
  // we will treat it as a hit
  // if we need to update meta from Trunk to Dirty
  // go update it
  val s2_hit = s2_tag_match && s2_has_permission
  val s2_store_hit = s2_hit && !s2_req.miss && !s2_req.probe && s2_req.source === STORE_SOURCE.U
  val s2_amo_hit   = s2_hit && !s2_req.miss && !s2_req.probe && s2_req.source === AMO_SOURCE.U

  when (s2_valid) {
    XSDebug("MainPipe: s2 s2_tag_match: %b s2_has_permission: %b s2_hit: %b s2_need_replacement: %b s2_way_en: %x s2_state: %d\n",
      s2_tag_match, s2_has_permission, s2_hit, s2_need_replacement, s2_way_en, s2_coh.state)
  }

  // --------------------------------------------------------------------------------
  // Write to MetaArray

  // whether we need to update meta

  // miss should always update meta
  val miss_update_meta = s2_req.miss
  val probe_update_meta = s2_req.probe && s2_tag_match && s2_coh =/= probe_new_coh
  // store only update meta when it hits and needs to update Trunk to Dirty
  val store_update_meta = s2_store_hit && s2_hit_coh =/= s2_new_hit_coh
  val amo_update_meta = s2_amo_hit && s2_hit_coh =/= s2_new_hit_coh
  val update_meta = miss_update_meta || probe_update_meta || store_update_meta || amo_update_meta

  val new_coh = Mux(miss_update_meta, miss_new_coh,
    Mux(probe_update_meta, probe_new_coh,
      Mux(store_update_meta || amo_update_meta, s2_new_hit_coh, ClientMetadata.onReset)))

  io.meta_write.valid         := s2_fire && update_meta
  io.meta_write.bits.idx      := get_idx(s2_req.addr)
  io.meta_write.bits.data.coh := new_coh
  io.meta_write.bits.data.tag := get_tag(s2_req.addr)
  io.meta_write.bits.way_en   := s2_way_en


  // --------------------------------------------------------------------------------
  // Write to DataArray
  // Miss:
  //   1. not store and not amo, data: store_data mask: store_mask(full_mask)
  //   2. store, data: store_data mask: store_mask(full_mask)
  //   3. amo, data: merge(store_data, amo_data, amo_mask) mask: store_mask(full_mask)
  // 
  // Probe: do not write data, DontCare
  // Store hit: data: merge(s2_data, store_data, store_mask) mask: store_mask
  // AMO hit: data: merge(s2_data, amo_data, amo_mask) mask: store_mask
  // so we can first generate store data and then merge with amo_data

  // generate write mask
  // which word do we need to write
  val wmask = Mux(s2_req.miss, s2_full_wmask,
      Mux(s2_store_hit, s2_store_wmask,
      Mux(s2_amo_hit, s2_amo_wmask,
        s2_none_wmask)))
  val need_write_data = VecInit(wmask.map(w => w.orR)).asUInt.orR

  // generate write data
  val store_data_merged = Wire(Vec(blockRows, UInt(rowBits.W)))

  def mergePutData(old_data: UInt, new_data: UInt, wmask: UInt): UInt = {
    val full_wmask = FillInterleaved(8, wmask)
    ((~full_wmask & old_data) | (full_wmask & new_data))
  }

  val s2_data = Mux1H(s2_way_en, s2_data_resp)

  val s2_data_decoded = (0 until blockRows) map { r =>
    (0 until rowWords) map { w =>
      val data = s2_data(r)(encWordBits * (w + 1) - 1, encWordBits * w)
      val decoded = cacheParams.dataCode.decode(data)
      assert(!(s2_valid && s2_hit && s2_rmask(r) && decoded.uncorrectable))
      decoded.corrected
    }
  }

  // TODO: deal with ECC errors
  for (i <- 0 until blockRows) {
    store_data_merged(i) := Cat((0 until rowWords).reverse map { w =>
      val old_data = s2_data_decoded(i)(w)
      val new_data = s2_req.store_data(rowBits * (i + 1) - 1, rowBits * i)(wordBits * (w + 1) - 1, wordBits * w)
      val wmask = s2_req.store_mask(rowBytes * (i + 1) - 1, rowBytes * i)(wordBytes * (w + 1) - 1, wordBytes * w)
      val store_data = mergePutData(old_data, new_data, wmask)
      store_data
    })
  }

  val amo_data_merged = Wire(Vec(blockRows, UInt(rowBits.W)))
  for (i <- 0 until blockRows) {
    amo_data_merged(i) := store_data_merged(i)
  }
  // TODO: do amo calculation
  // and merge amo data
  /*
  for (i <- 0 until blockRows) {
    store_data_merged(i) := Cat((0 until rowWords).reverse map { w =>
      val old_data = store_data_merged(i)(w)
      val wmask = Mux(s2_req.source === AMO_SOURCE.U && (s2_req.miss || s2_hit) && s2_req.word_idx === i.U, s2_req.amo_mask, 0.U)
      val store_data = mergePutData(old_data, new_data, wmask)
    })
  }
  */

  // ECC encode data
  val wdata_merged = Wire(Vec(blockRows, UInt(encRowBits.W)))
  for (i <- 0 until blockRows) {
    wdata_merged(i) := Cat((0 until rowWords).reverse map { w =>
      val wdata = amo_data_merged(i)(wordBits * (w + 1) - 1, wordBits * w)
      val wdata_encoded = cacheParams.dataCode.encode(wdata)
      wdata_encoded
    })
  }

  val data_write = io.data_write.bits
  io.data_write.valid := s2_fire && need_write_data
  data_write.rmask    := DontCare
  data_write.way_en   := s2_way_en
  data_write.addr     := s2_req.addr
  data_write.wmask    := wmask
  data_write.data     := wdata_merged

  assert(!(io.data_write.valid && !io.data_write.ready))

  // --------------------------------------------------------------------------------
  // Writeback
  // whether we need to write back a block
  // TODO: add support for ProbePerm
  // Now, we only deal with ProbeBlock
  val miss_writeback  = s2_need_replacement && s2_coh === ClientStates.Dirty
  // even probe missed, we still need to use write back to send ProbeAck NtoN response
  // val probe_writeback = s2_req.probe && s2_tag_match && s2_coh.state =/= probe_new_coh.state
  val probe_writeback = s2_req.probe
  val need_writeback  = miss_writeback || probe_writeback

  val writeback_addr  = Cat(s2_meta.tag, get_idx(s2_req.addr)) << blockOffBits

  val (_, miss_shrink_param, _) = s2_coh.onCacheControl(M_FLUSH)
  val writeback_param = Mux(miss_writeback, miss_shrink_param, probe_shrink_param)

  val writeback_data = s2_coh === ClientStates.Dirty

  val wb_req = io.wb_req.bits
  io.wb_req.valid  := s2_valid && need_writeback
  wb_req.addr      := writeback_addr
  wb_req.param     := writeback_param
  wb_req.voluntary := miss_writeback
  wb_req.hasData   := writeback_data
  wb_req.data      := VecInit(s2_data_decoded.flatten).asUInt

  stall := io.wb_req.valid && !io.wb_req.ready
  when (stall) {
    XSDebug("stall\n")
  }

  // --------------------------------------------------------------------------------
  // send store/amo miss to miss queue
  val store_amo_miss = !s2_req.miss && !s2_req.probe && !s2_hit && (s2_req.source === STORE_SOURCE.U || s2_req.source === AMO_SOURCE.U)
  io.miss_req.valid           := s2_fire && store_amo_miss
  io.miss_req.bits.source     := s2_req.source
  io.miss_req.bits.cmd        := s2_req.cmd
  io.miss_req.bits.addr       := s2_req.addr
  io.miss_req.bits.store_data := s2_req.store_data
  io.miss_req.bits.store_mask := s2_req.store_mask
  io.miss_req.bits.word_idx   := s2_req.word_idx
  io.miss_req.bits.amo_data   := s2_req.amo_data
  io.miss_req.bits.amo_mask   := s2_req.amo_mask
  io.miss_req.bits.coh        := s2_coh
  io.miss_req.bits.id         := s2_req.id

  // --------------------------------------------------------------------------------
  // send response
  val resp = Wire(new MainPipeResp)
  // TODO: add amo data out
  resp.data := DontCare
  resp.id   := s2_req.id
  resp.miss := store_amo_miss
  resp.replay := io.miss_req.valid && !io.miss_req.ready

  io.miss_resp.valid   := s2_fire && s2_req.miss
  io.miss_resp.bits    := resp
  io.miss_resp.bits.id := s2_req.miss_id

  io.store_resp.valid  := s2_fire && s2_req.source === STORE_SOURCE.U
  io.store_resp.bits   := resp

  io.amo_resp.valid    := s2_fire && s2_req.source === AMO_SOURCE.U
  io.amo_resp.bits     := resp

  when (io.req.fire()) {
    io.req.bits.dump()
  }

  when (io.miss_req.fire()) {
    io.miss_req.bits.dump()
  }

  when (io.miss_resp.fire()) {
    io.miss_resp.bits.dump()
  }

  when (io.store_resp.fire()) {
    io.store_resp.bits.dump()
  }

  when (io.amo_resp.fire()) {
    io.amo_resp.bits.dump()
  }

  when (io.wb_req.fire()) {
    io.wb_req.bits.dump()
  }

  when (io.lrsc_locked_block.valid) {
    XSDebug("lrsc_locked_block: %x\n", io.lrsc_locked_block.bits)
  }

  // -------
  // Debug logging functions
  def dump_pipeline_reqs(pipeline_stage_name: String, valid: Bool, req: MainPipeReq) = {
    when (valid) {
      XSDebug(s"$pipeline_stage_name ")
      req.dump()
    }
  }
}
