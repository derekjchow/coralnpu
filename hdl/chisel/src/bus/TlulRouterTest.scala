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

package bus

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec

class TlulRouterSpec extends AnyFreeSpec with ChiselSim {
  val p = new TLULParameters(dataBits = 32, addrBits = 32, idBits = 1)

  // Route 0: [0x0000, 0x1000). Route 1: [0x0000, 0x2000) — overlaps route 0 to
  // exercise first-match-wins. Route 2: catch-all.
  def buildRouter() = new TlulRouter(
    p,
    () => new NoUser,
    () => new NoUser,
    Seq(
      (addr: UInt) => addr < 0x1000.U,
      (addr: UInt) => addr < 0x2000.U,
      (addr: UInt) => true.B
    )
  )

  "Routes requests by address" in {
    simulate(buildRouter()) { dut =>
      for ((addr, dev) <- Seq((0x10, 0), (0x1800, 1), (0x123400, 2))) {
        dut.io.host.a.valid.poke(true.B)
        dut.io.host.a.bits.address.poke(addr.U)
        dut.io.host.a.bits.opcode.poke(TLULOpcodesA.Get.asUInt)
        for (i <- 0 until 3) {
          dut.io.devs(i).a.ready.poke(false.B)
        }
        for (i <- 0 until 3) {
          dut.io.devs(i).a.valid.expect((i == dev).B)
        }
        // Not ready until the selected device is ready.
        dut.io.host.a.ready.expect(false.B)
        dut.io.devs(dev).a.ready.poke(true.B)
        dut.io.host.a.ready.expect(true.B)
        dut.io.devs(dev).a.bits.address.expect(addr.U)
        // Drop the request without firing to keep router state idle.
        dut.io.host.a.valid.poke(false.B)
        dut.io.devs(dev).a.ready.poke(false.B)
        dut.clock.step()
      }
    }
  }

  "First matching route wins on overlap" in {
    simulate(buildRouter()) { dut =>
      dut.io.host.a.valid.poke(true.B)
      dut.io.host.a.bits.address.poke(0x800.U) // Matches routes 0 and 1.
      dut.io.devs(0).a.valid.expect(true.B)
      dut.io.devs(1).a.valid.expect(false.B)
      dut.io.devs(2).a.valid.expect(false.B)
    }
  }

  "Merges D responses onto the host" in {
    simulate(buildRouter()) { dut =>
      // Fire a request to device 1 so a response is outstanding.
      dut.io.host.a.valid.poke(true.B)
      dut.io.host.a.bits.address.poke(0x1800.U)
      dut.io.devs(1).a.ready.poke(true.B)
      dut.clock.step()
      dut.io.host.a.valid.poke(false.B)

      dut.io.devs(1).d.valid.poke(true.B)
      dut.io.devs(1).d.bits.data.poke(0x1234.U)
      dut.io.devs(1).d.bits.opcode.poke(TLULOpcodesD.AccessAckData.asUInt)
      dut.io.host.d.ready.poke(true.B)
      dut.io.host.d.valid.expect(true.B)
      dut.io.host.d.bits.data.expect(0x1234.U)
      dut.io.devs(1).d.ready.expect(true.B)
      dut.io.devs(0).d.ready.expect(false.B)
      dut.clock.step()
      dut.io.devs(1).d.valid.poke(false.B)
      dut.io.host.d.valid.expect(false.B)
    }
  }

  "Holds off requests to a different device while a response is outstanding" in {
    simulate(buildRouter()) { dut =>
      // Fire a request to device 0.
      dut.io.host.a.valid.poke(true.B)
      dut.io.host.a.bits.address.poke(0x10.U)
      dut.io.devs(0).a.ready.poke(true.B)
      dut.io.host.a.ready.expect(true.B)
      dut.clock.step()

      // A request to device 1 must stall while device 0 owes a response.
      dut.io.host.a.bits.address.poke(0x1800.U)
      dut.io.devs(1).a.ready.poke(true.B)
      dut.io.host.a.ready.expect(false.B)
      dut.io.devs(1).a.valid.expect(false.B)

      // A second request to the same device is allowed.
      dut.io.host.a.bits.address.poke(0x20.U)
      dut.io.host.a.ready.expect(true.B)

      // Return device 0's response; device 1 becomes reachable.
      dut.io.host.a.bits.address.poke(0x1800.U)
      dut.io.devs(0).d.valid.poke(true.B)
      dut.io.devs(0).d.bits.opcode.poke(TLULOpcodesD.AccessAckData.asUInt)
      dut.io.host.d.ready.poke(true.B)
      dut.clock.step()
      dut.io.devs(0).d.valid.poke(false.B)
      dut.io.host.a.ready.expect(true.B)
      dut.io.devs(1).a.valid.expect(true.B)
    }
  }
}
