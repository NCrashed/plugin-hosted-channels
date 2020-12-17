package fr.acinq.hc.app.channel

import fr.acinq.eclair._
import fr.acinq.eclair.channel.{CMD_FULFILL_HTLC, CMD_SIGN, NORMAL, OFFLINE}
import fr.acinq.eclair.io.PeerDisconnected
import fr.acinq.eclair.wire.{ChannelUpdate, UpdateFulfillHtlc}
import fr.acinq.hc.app.{HCTestUtils, InvokeHostedChannel, LastCrossSignedState, ResizeChannel, StateUpdate, Worker}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class HCResizeSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with HCStateTestsHelperMethods {

  protected type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = withFixture(test.toNoArgTest(init()))

  test("Resize without in-flight HTLCs") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.capacity == 10000000L.sat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 0L.msat)
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, 50000000000L.msat)
    alice ! bob2alice.expectMsgType[ResizeChannel]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.capacity == 50000000L.sat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.capacity == 50000000L.sat)
    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(9000000000L.msat, f, currentBlockHeight)
    val (preimage2, alice2bobUpdateAdd2) = addHtlcFromAliceToBob(9000000000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd1.id, preimage1, f)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd2.id, preimage2, f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 32000000000L.msat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 18000000000L.msat)
  }

  test("Resize with HTLCs in-flight") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(5000000000L.msat, f, currentBlockHeight)
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f) // To give Bob some money
    val (preimage1, alice2bobUpdateAdd1) = addHtlcFromAliceToBob(2000000000L.msat, f, currentBlockHeight)
    val (preimage2, bob2aliceUpdateAdd2) = addHtlcFromBob2Alice(2000000000L.msat, f)
    alice ! CMD_FULFILL_HTLC(bob2aliceUpdateAdd2.id, preimage2)
    alice ! CMD_SIGN(None)
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, 20000000000L.msat)
    alice ! bob2alice.expectMsgType[ResizeChannel]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[UpdateFulfillHtlc]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob ! CMD_FULFILL_HTLC(alice2bobUpdateAdd1.id, preimage1)
    bob ! CMD_SIGN(None)
    alice ! bob2alice.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[UpdateFulfillHtlc]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.originChannels.isEmpty)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 15000000000L.msat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 5000000000L.msat)
  }

  test("Host does not get resize proposal before restart") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(5000000000L.msat, f, currentBlockHeight)
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, 20000000000L.msat)
    bob2alice.expectMsgType[ResizeChannel] // Alice does not get it
    bob2alice.expectMsgType[StateUpdate]

    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[ResizeChannel]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()
    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f)
    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 15000000000L.msat)
    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 5000000000L.msat)
  }

  test("Host applies resize proposal before restart") { f =>
    import f._
    HCTestUtils.resetEntireDatabase(aliceDB)
    HCTestUtils.resetEntireDatabase(bobDB)
    reachNormal(f)
    val (preimage0, alice2bobUpdateAdd0) = addHtlcFromAliceToBob(5000000000L.msat, f, currentBlockHeight)
    bob ! HC_CMD_RESIZE(bobKit.nodeParams.nodeId, 20000000000L.msat)
    alice ! bob2alice.expectMsgType[ResizeChannel]
    alice ! bob2alice.expectMsgType[StateUpdate]
    alice2bob.expectMsgType[StateUpdate] // Bob does not get it
    bob2alice.expectNoMessage()
    alice2bob.expectNoMessage()

    alice ! PeerDisconnected(null, null)
    bob ! PeerDisconnected(null, null)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    bob ! Worker.HCPeerConnected
    alice ! Worker.HCPeerConnected
    alice ! bob2alice.expectMsgType[InvokeHostedChannel]
    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[LastCrossSignedState]
    alice ! bob2alice.expectMsgType[ResizeChannel]
    alice ! bob2alice.expectMsgType[ChannelUpdate]
    alice ! bob2alice.expectMsgType[StateUpdate]

    bob ! alice2bob.expectMsgType[LastCrossSignedState]
    bob ! alice2bob.expectMsgType[ChannelUpdate]
    bob ! alice2bob.expectMsgType[StateUpdate]
//    bob ! alice2bob.expectMsgType[ResizeChannel]

//    alice ! bob2alice.expectMsgType[LastCrossSignedState]
//    alice ! bob2alice.expectMsgType[ResizeChannel]
//    alice ! bob2alice.expectMsgType[ChannelUpdate]
//
//    bob ! alice2bob.expectMsgType[LastCrossSignedState]
//    alice ! bob2alice.expectMsgType[StateUpdate]
//    bob ! alice2bob.expectMsgType[ChannelUpdate]
//    bob ! alice2bob.expectMsgType[StateUpdate]
//    alice ! bob2alice.expectMsgType[StateUpdate]
//    bob2alice.expectNoMessage()
//    alice2bob.expectNoMessage()
//    fulfillAliceHtlcByBob(alice2bobUpdateAdd0.id, preimage0, f)
//    awaitCond(alice.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 15000000000L.msat)
//    awaitCond(bob.stateData.asInstanceOf[HC_DATA_ESTABLISHED].commitments.localSpec.toLocal == 5000000000L.msat)
  }
}
