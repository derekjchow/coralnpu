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
import chisel3.util._

// Routes TL-UL requests from one host to one of several devices based on the
// request address. `routes(i)` is an address predicate selecting device i;
// when several predicates match, the lowest index wins. Callers must supply a
// catch-all route (e.g. `_ => true.B`) if unmatched addresses are possible.
//
// The A channel is steered combinationally, so request fields stay stable
// while valid is held and a stalling device backpressures the host directly.
// D responses are merged assuming devices respond in request order; to
// guarantee that, a request for device j is held off while responses from a
// different device are still outstanding.
class TlulRouter[A_USER <: Data, D_USER <: Data](
  p: TLULParameters,
  userAGen: () => A_USER,
  userDGen: () => D_USER,
  routes: Seq[UInt => Bool]
) extends Module {
  require(routes.nonEmpty)
  val n = routes.length

  val io = IO(new Bundle {
    val host = new TLULDevice2Host(p, userAGen, userDGen)
    val devs = Vec(n, new TLULHost2Device(p, userAGen, userDGen))
  })

  // ==========================================================================
  // A channel steering
  val hits   = VecInit(routes.map(r => r(io.host.a.bits.address)))
  val anyHit = hits.asUInt.orR
  val sel    = PriorityEncoder(hits)
  assert(!io.host.a.valid || anyHit, "TlulRouter: no route matches request address")

  // Outstanding request tracking. Responses are merged in arrival order, so
  // only allow a request to a new device once all prior responses returned.
  val pending    = RegInit(0.U(2.W))
  val pendingDev = RegInit(0.U(log2Ceil(math.max(n, 2)).W))
  val allow      = (pending === 0.U) || (sel === pendingDev)

  for (i <- 0 until n) {
    io.devs(i).a.valid := io.host.a.valid && anyHit && allow && (sel === i.U)
    io.devs(i).a.bits  := io.host.a.bits
  }
  io.host.a.ready := anyHit && allow && VecInit(io.devs.map(_.a.ready))(sel)

  // ==========================================================================
  // D channel merge
  val dValids = VecInit(io.devs.map(_.d.valid))
  assert(PopCount(dValids.asUInt) <= 1.U, "TlulRouter: multiple devices responding at once")

  io.host.d.valid := dValids.asUInt.orR
  io.host.d.bits  := Mux1H(dValids, io.devs.map(_.d.bits))
  for (i <- 0 until n) {
    io.devs(i).d.ready := io.host.d.ready && dValids(i)
  }

  // ==========================================================================
  // Outstanding state update
  val aFired = io.host.a.fire
  val dFired = io.host.d.fire
  pending := MuxCase(
    pending,
    Seq(
      (aFired && !dFired)                    -> (pending + 1.U),
      (!aFired && dFired && (pending > 0.U)) -> (pending - 1.U)
    )
  )
  assert(!(aFired && !dFired) || (pending < 3.U), "TlulRouter: pending counter overflow")
  when(aFired) {
    pendingDev := sel
  }
}
