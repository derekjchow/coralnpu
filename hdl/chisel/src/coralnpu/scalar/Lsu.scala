// Copyright 2023 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package coralnpu

import chisel3._
import chisel3.util._
import common._
import coralnpu.rvv._

class DFlushFenceiIO(p: Parameters) extends DFlushIO(p) {
  val fencei = Output(Bool())
  val pcNext = Output(UInt(p.programCounterBits.W))
}

class Lsu(p: Parameters) extends Module {
  val io = IO(new Bundle {
    // Decode cycle.
    val req         = Vec(p.instructionLanes, Flipped(Decoupled(new LsuCmd(p))))
    val busPort     = Flipped(new RegfileBusPortIO(p))
    val busPort_flt = Option.when(p.enableFloat)(Flipped(new RegfileBusPortIO(p)))

    // Execute cycle(s).
    val rd     = Valid(Flipped(new RegfileWriteDataIO(p)))
    val rd_flt = Valid(Flipped(new FloatRegfileWriteDataIO(p)))

    // Cached interface.
    val ibus  = new IBusIO(p)
    val dbus  = new DBusIO(p)
    val flush = new DFlushFenceiIO(p)
    val fault = Valid(new FaultInfo(p))

    // DBus that will eventually reach an external bus.
    // Intended for sending a transaction to an external
    // peripheral, likely on TileLink or AXI.
    val ebus = new EBusIO(p)

    // Vector switch.
    val vldst = Output(Bool())

    val rvv2lsu = Option.when(p.enableRvv)(Vec(2, Flipped(Decoupled(new Rvv2Lsu(p)))))
    val lsu2rvv = Option.when(p.enableRvv)(Vec(2, Decoupled(new Lsu2Rvv(p))))

    // RVV config state
    val rvvState = Option.when(p.enableRvv)(Input(Valid(new RvvConfigState(p))))

    val queueCapacity = Output(UInt(3.W))
    val active        = Output(Bool())
    val storeComplete = Output(Valid(UInt(p.programCounterBits.W)))
  })
}

object Lsu {
  def apply(p: Parameters): Lsu = {
    return Module(new LsuV2(p))
    // TODO: switch to new LSU
    // Module(new LsuV3(p))
  }
}

object LsuOp extends ChiselEnum {
  val LB       = Value
  val LH       = Value
  val LW       = Value
  val LBU      = Value
  val LHU      = Value
  val SB       = Value
  val SH       = Value
  val SW       = Value
  val FENCEI   = Value
  val FLUSHAT  = Value
  val FLUSHALL = Value
  val VLDST    = Value
  val FLOAT    = Value

  // Vector instructions.
  val VLOAD_UNIT      = Value
  val VLOAD_STRIDED   = Value
  val VLOAD_OINDEXED  = Value
  val VLOAD_UINDEXED  = Value
  val VSTORE_UNIT     = Value
  val VSTORE_STRIDED  = Value
  val VSTORE_OINDEXED = Value
  val VSTORE_UINDEXED = Value

  def isVector(op: LsuOp.Type): Bool = {
    op.isOneOf(
      LsuOp.VLOAD_UNIT,
      LsuOp.VLOAD_STRIDED,
      LsuOp.VLOAD_OINDEXED,
      LsuOp.VLOAD_UINDEXED,
      LsuOp.VSTORE_UNIT,
      LsuOp.VSTORE_STRIDED,
      LsuOp.VSTORE_OINDEXED,
      LsuOp.VSTORE_UINDEXED
    )
  }

  def isIndexedVector(op: LsuOp.Type): Bool = {
    op.isOneOf(
      LsuOp.VLOAD_OINDEXED,
      LsuOp.VLOAD_UINDEXED,
      LsuOp.VSTORE_OINDEXED,
      LsuOp.VSTORE_UINDEXED
    )
  }

  def isNonindexedVector(op: LsuOp.Type): Bool = {
    op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VLOAD_STRIDED, LsuOp.VSTORE_UNIT, LsuOp.VSTORE_STRIDED)
  }

  def isFlush(op: LsuOp.Type): Bool = {
    op.isOneOf(LsuOp.FENCEI, LsuOp.FLUSHAT, LsuOp.FLUSHALL)
  }

  def isScalarLoad(op: LsuOp.Type): Bool = {
    op.isOneOf(LsuOp.LB, LsuOp.LBU, LsuOp.LH, LsuOp.LHU, LsuOp.LW)
  }

  def opSize(op: LsuOp.Type, address: UInt, p: Parameters): (UInt, UInt) = {
    val halfAligned = (address(0) === 0.U)
    val wordAligned = (address(1, 0) === 0.U)

    val size = MuxUpTo1H(
      16.U,
      Seq(
        op.isOneOf(LsuOp.LB, LsuOp.LBU, LsuOp.SB)   -> 1.U,
        op.isOneOf(LsuOp.LH, LsuOp.LHU, LsuOp.SH)   -> Mux(halfAligned, 2.U, 16.U),
        op.isOneOf(LsuOp.LW, LsuOp.SW, LsuOp.FLOAT) ->
          Mux(wordAligned, 4.U, 16.U),
        LsuOp.isVector(op) -> 16.U
      )
    )

    val halfAlignedAddress = address(p.lsuAddrBits - 1, 1) << 1.U
    val wordAlignedAddress = address(p.lsuAddrBits - 1, 2) << 2.U
    val lineAlignedAddress = address(p.lsuAddrBits - 1, 4) << 4.U
    val alignedAddress     = MuxUpTo1H(
      lineAlignedAddress,
      Seq(
        op.isOneOf(LsuOp.LB, LsuOp.LBU, LsuOp.SB)                  -> address,
        (op.isOneOf(LsuOp.LH, LsuOp.LHU, LsuOp.SH) && halfAligned) ->
          halfAlignedAddress,
        (op.isOneOf(LsuOp.LW, LsuOp.SW, LsuOp.FLOAT) && wordAligned) ->
          wordAlignedAddress
      )
    )

    (size, alignedAddress)
  }
}

class LsuCmd(p: Parameters) extends Bundle {
  val store     = Bool()
  val addr      = UInt(log2Ceil(p.scalarRegCount).W)
  val op        = LsuOp()
  val pc        = UInt(p.programCounterBits.W)
  val elemWidth = Option.when(p.enableRvv) { UInt(3.W) }
  val nfields   = Option.when(p.enableRvv) { UInt(3.W) }
  val bit24To20 = Option.when(p.enableRvv) { UInt(5.W) }

  def umop = bit24To20 // when unit-stride
  def rs2  = bit24To20 // when const-stride

  def isMaskOperation(): Bool = {
    if (p.enableRvv) {
      (umop.get === "b01011".U) &&
      op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VSTORE_UNIT)
    } else {
      false.B
    }
  }

  def isWholeRegister(): Bool = {
    if (p.enableRvv) {
      (umop.get === "b01000".U) &&
      op.isOneOf(LsuOp.VLOAD_UNIT, LsuOp.VSTORE_UNIT)
    } else {
      false.B
    }
  }

  override def toPrintable: Printable = {
    cf"LsuCmd(store -> ${store}, addr -> 0x${addr}%x, op -> ${op}, " +
      cf"pc -> 0x${pc}%x, elemWidth -> ${elemWidth}, nfields -> ${nfields})"
  }
}

class LsuUOp(p: Parameters) extends Bundle {
  val store = Bool()
  val rd    = UInt(log2Ceil(p.scalarRegCount).W)
  val op    = LsuOp()
  val pc    = UInt(p.programCounterBits.W)
  val addr  = UInt(p.lsuAddrBits.W)
  val data  = UInt(p.xlen.W) // Doubles as rs2
  // This aligns with "width" in the spec. It controls index width in
  // indexed loads/stores and data width otherwise.
  val elemWidth = Option.when(p.enableRvv) { UInt(3.W) }
  // This is the sew from vtype. It controls data width in indexed
  // loads/stores and is unused in other ops.
  val sew = Option.when(p.enableRvv) { UInt(3.W) }
  // How many data registers (per segment if applicable) to operate on.
  val emul_data      = Option.when(p.enableRvv) { UInt(3.W) }
  val emul_data_orig = Option.when(p.enableRvv) { UInt(3.W) }
  val nfields        = Option.when(p.enableRvv) { UInt(3.W) }
  val strict         = Option.when(p.enableRvv) { Bool() }

  override def toPrintable: Printable = {
    cf"LsuUOp(store -> ${store}, rd -> ${rd}, op -> ${op}, " +
      cf"pc -> 0x${pc}%x, addr -> 0x${addr}%x, data -> ${data})"
  }
}

object LsuUOp {
  def apply(
    p: Parameters,
    i: Int,
    cmd: LsuCmd,
    sbus: RegfileBusPortIO,
    fbus: Option[RegfileBusPortIO],
    rvvState: Option[Valid[RvvConfigState]]
  ): LsuUOp = {
    val result = Wire(new LsuUOp(p))
    result.store := cmd.store
    result.rd    := cmd.addr
    result.op    := cmd.op
    result.pc    := cmd.pc
    if (fbus.isDefined) {
      result.addr := sbus.addr(i)
      result.data := Mux(cmd.op === LsuOp.FLOAT, fbus.get.data(i), sbus.data(i))
    } else {
      result.addr := sbus.addr(i)
      result.data := sbus.data(i)
    }
    if (p.enableRvv) {
      val eew       = cmd.elemWidth.get     // From instruction encoding
      val sew       = rvvState.get.bits.sew // From vtype
      val lmul_eff  = rvvState.get.bits.lmul
      val lmul_orig = rvvState.get.bits.lmul_orig
      // TODO(davidgao): Add checks for illegal LMUL values in the frontend.
      def lmulToDataEmul(lmul: UInt): UInt = {
        // Unit-stride, const-stride. Default value applies when eew == sew.
        val emul_data = MuxUpTo1H(
          lmul,
          Seq(
            // eew == 1/4 sew
            (eew === "b000".U && sew === "b010".U) -> (lmul - 2.U),
            // eew == 1/2 sew
            ((eew === "b000".U && sew === "b001".U) ||
              (eew === "b101".U && sew === "b010".U)) -> (lmul - 1.U),
            // eew == 2 sew
            ((eew === "b101".U && sew === "b000".U) ||
              (eew === "b110".U && sew === "b001".U)) -> (lmul + 1.U),
            // eew == 4 sew
            (eew === "b110".U && sew === "b000".U) -> (lmul + 2.U)
          )
        )
        MuxCase(
          lmul,
          Seq(
            // If mask operation, always make LMUL=1.
            cmd.isMaskOperation() -> 0.U,
            // Section 7.9 of RVV Spec: "The nf field encodes how many vector
            // registers to load and store".
            cmd.isWholeRegister() -> MuxUpTo1H(
              0.U,
              Seq(
                (cmd.nfields.get === 0.U) -> 0.U, // NF1 -> LMUL1
                (cmd.nfields.get === 1.U) -> 1.U, // NF2 -> LMUL2
                (cmd.nfields.get === 3.U) -> 2.U, // NF4 -> LMUL4
                (cmd.nfields.get === 7.U) -> 3.U  // NF8 -> LMUL8
              )
            ),
            LsuOp.isNonindexedVector(cmd.op) -> emul_data
            // default: indexed vector and scalar
          )
        )
      }

      result.elemWidth.get      := eew
      result.emul_data.get      := lmulToDataEmul(lmul_eff)
      result.emul_data_orig.get := lmulToDataEmul(lmul_orig)

      // If mask operation, force fields to zero
      result.nfields.get := MuxUpTo1H(
        cmd.nfields.get,
        Seq(
          cmd.isMaskOperation() -> 0.U,
          cmd.isWholeRegister() -> 0.U
        )
      )
      result.sew.get := rvvState.get.bits.sew
      // We only care about const stride here.
      // Ordered indexed is apparent on the op.
      result.strict.get := (
        cmd.op.isOneOf(LsuOp.VLOAD_STRIDED, LsuOp.VSTORE_STRIDED) &&
          cmd.rs2.get =/= 0.U &&
          sbus.data(i) === 0.U
      )
    }

    result
  }
}
