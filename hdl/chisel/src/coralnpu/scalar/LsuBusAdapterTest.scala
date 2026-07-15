// Copyright 2026 Google LLC
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
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import bus._

class LsuBusAdapterSpec extends AnyFreeSpec with ChiselSim {
  // Default regions: ITCM [0x0, 0x2000), DTCM [0x10000, 0x18000),
  // Peripheral [0x30000, 0x31000). 128-bit bus => 16-byte lines.
  def makeParams(): Parameters = {
    val p = Parameters(MemoryRegions.default)
    p.lsuDataBits = 128
    p
  }

  def pokeRequest(
    dut: LsuBusAdapter,
    addr: BigInt,
    write: Boolean = false,
    size: Int = 4,
    pc: BigInt = 0x100
  ): Unit = {
    dut.io.tl.a.valid.poke(true.B)
    dut.io.tl.a.bits.opcode.poke(
      if (write) { TLULOpcodesA.PutPartialData.asUInt }
      else { TLULOpcodesA.Get.asUInt }
    )
    dut.io.tl.a.bits.address.poke(addr.U)
    dut.io.tl.a.bits.size.poke(size.U)
    dut.io.tl.a.bits.user.pc.poke(pc.U)
  }

  def expectOnlyValid(dut: LsuBusAdapter, ibus: Boolean, dbus: Boolean, ebus: Boolean): Unit = {
    dut.io.ibus.valid.expect(ibus.B)
    dut.io.dbus.valid.expect(dbus.B)
    dut.io.ebus.dbus.valid.expect(ebus.B)
  }

  "DTCM read routes to dbus with line-aligned address" in {
    simulate(new LsuBusAdapter(makeParams())) { dut =>
      pokeRequest(dut, 0x10044, size = 2)
      dut.io.dbus.ready.poke(true.B)
      dut.io.tl.d.ready.poke(true.B)
      expectOnlyValid(dut, ibus = false, dbus = true, ebus = false)
      dut.io.dbus.addr.expect(0x10040.U)
      dut.io.dbus.adrx.expect(0x10040.U)
      dut.io.dbus.size.expect(4.U) // 2^2 bytes
      dut.io.dbus.write.expect(false.B)
      dut.io.tl.a.ready.expect(true.B)
      dut.clock.step()

      dut.io.tl.a.valid.poke(false.B)
      dut.io.dbus.rdata.poke(BigInt("deadbeef", 16).U)
      dut.io.tl.d.valid.expect(true.B)
      dut.io.tl.d.bits.opcode.expect(TLULOpcodesD.AccessAckData.asUInt)
      dut.io.tl.d.bits.data.expect(BigInt("deadbeef", 16).U)
      dut.io.tl.d.bits.error.expect(false.B)
    }
  }

  "DTCM write routes to dbus and acks" in {
    simulate(new LsuBusAdapter(makeParams())) { dut =>
      pokeRequest(dut, 0x10040, write = true, pc = 0x1234)
      dut.io.tl.a.bits.data.poke(BigInt("cafef00d", 16).U)
      dut.io.tl.a.bits.mask.poke(0xf.U)
      dut.io.dbus.ready.poke(true.B)
      dut.io.tl.d.ready.poke(true.B)
      expectOnlyValid(dut, ibus = false, dbus = true, ebus = false)
      dut.io.dbus.write.expect(true.B)
      dut.io.dbus.wdata.expect(BigInt("cafef00d", 16).U)
      dut.io.dbus.wmask.expect(0xf.U)
      dut.io.dbus.pc.expect(0x1234.U)
      dut.clock.step()

      dut.io.tl.a.valid.poke(false.B)
      dut.io.tl.d.valid.expect(true.B)
      dut.io.tl.d.bits.opcode.expect(TLULOpcodesD.AccessAck.asUInt)
      dut.io.tl.d.bits.error.expect(false.B)
    }
  }

  "ITCM read routes to ibus" in {
    simulate(new LsuBusAdapter(makeParams())) { dut =>
      pokeRequest(dut, 0x44)
      dut.io.ibus.ready.poke(true.B)
      dut.io.tl.d.ready.poke(true.B)
      expectOnlyValid(dut, ibus = true, dbus = false, ebus = false)
      dut.io.ibus.addr.expect(0x40.U)
      dut.io.tl.a.ready.expect(true.B)
      dut.clock.step()

      dut.io.tl.a.valid.poke(false.B)
      dut.io.ibus.rdata.poke(0x12345678.U)
      dut.io.tl.d.valid.expect(true.B)
      dut.io.tl.d.bits.opcode.expect(TLULOpcodesD.AccessAckData.asUInt)
      dut.io.tl.d.bits.data.expect(0x12345678.U)
      dut.io.tl.d.bits.error.expect(false.B)
    }
  }

  "ITCM store issues no bus transaction and errors" in {
    simulate(new LsuBusAdapter(makeParams())) { dut =>
      pokeRequest(dut, 0x40, write = true)
      dut.io.ibus.ready.poke(false.B)
      dut.io.tl.d.ready.poke(true.B)
      expectOnlyValid(dut, ibus = false, dbus = false, ebus = false)
      dut.io.tl.a.ready.expect(true.B) // Accepted despite ibus not ready.
      dut.clock.step()

      dut.io.tl.a.valid.poke(false.B)
      expectOnlyValid(dut, ibus = false, dbus = false, ebus = false)
      dut.io.tl.d.valid.expect(true.B)
      dut.io.tl.d.bits.opcode.expect(TLULOpcodesD.AccessAck.asUInt)
      dut.io.tl.d.bits.error.expect(true.B)
    }
  }

  "Peripheral read routes to ebus as internal and tolerates stalls" in {
    simulate(new LsuBusAdapter(makeParams())) { dut =>
      pokeRequest(dut, 0x30000)
      dut.io.ebus.dbus.ready.poke(false.B)
      dut.io.tl.d.ready.poke(true.B)
      for (_ <- 0 until 3) {
        expectOnlyValid(dut, ibus = false, dbus = false, ebus = true)
        dut.io.ebus.internal.expect(true.B)
        dut.io.tl.a.ready.expect(false.B)
        dut.clock.step()
      }
      dut.io.ebus.dbus.ready.poke(true.B)
      dut.io.tl.a.ready.expect(true.B)
      dut.clock.step()

      dut.io.tl.a.valid.poke(false.B)
      dut.io.ebus.dbus.rdata.poke(0x55.U)
      dut.io.tl.d.valid.expect(true.B)
      dut.io.tl.d.bits.data.expect(0x55.U)
      dut.io.tl.d.bits.error.expect(false.B)
    }
  }

  "External read routes to ebus with unmasked address" in {
    simulate(new LsuBusAdapter(makeParams())) { dut =>
      pokeRequest(dut, BigInt("40000004", 16))
      dut.io.ebus.dbus.ready.poke(true.B)
      dut.io.tl.d.ready.poke(true.B)
      expectOnlyValid(dut, ibus = false, dbus = false, ebus = true)
      dut.io.ebus.internal.expect(false.B)
      dut.io.ebus.dbus.addr.expect(BigInt("40000004", 16).U)
      dut.io.ebus.dbus.adrx.expect(BigInt("40000000", 16).U)
    }
  }

  "Ebus fault in the completion cycle errors the response" in {
    simulate(new LsuBusAdapter(makeParams())) { dut =>
      pokeRequest(dut, BigInt("40000000", 16))
      dut.io.ebus.dbus.ready.poke(true.B)
      dut.io.ebus.fault.valid.poke(true.B)
      dut.io.tl.d.ready.poke(true.B)
      dut.clock.step()

      dut.io.tl.a.valid.poke(false.B)
      dut.io.ebus.fault.valid.poke(false.B)
      dut.io.tl.d.valid.expect(true.B)
      dut.io.tl.d.bits.error.expect(true.B)
    }
  }

  "Backpressured D response holds and blocks new requests" in {
    simulate(new LsuBusAdapter(makeParams())) { dut =>
      pokeRequest(dut, 0x10000)
      dut.io.dbus.ready.poke(true.B)
      dut.io.tl.d.ready.poke(false.B)
      dut.clock.step()

      dut.io.dbus.rdata.poke(0x77.U)
      dut.clock.step() // Response parked in the skid buffer.

      // Response held stable; a follow-up request is not accepted.
      pokeRequest(dut, 0x10010)
      dut.io.tl.d.valid.expect(true.B)
      dut.io.tl.d.bits.data.expect(0x77.U)
      dut.io.tl.a.ready.expect(false.B)
      dut.io.dbus.valid.expect(false.B)

      // Draining the response unblocks the A channel in the same cycle.
      dut.io.tl.d.ready.poke(true.B)
      dut.io.tl.a.ready.expect(true.B)
    }
  }
}
