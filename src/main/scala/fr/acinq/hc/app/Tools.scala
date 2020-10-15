package fr.acinq.hc.app

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, LexicographicalOrdering, Protocol, Satoshi}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi, ShortChannelId}
import com.typesafe.config.{Config => Configuration}
import java.io.{ByteArrayInputStream, File}
import java.nio.file.{Files, Paths}

import net.ceedubs.ficus.readers.ValueReader
import com.google.common.cache.CacheBuilder
import com.typesafe.config.ConfigFactory
import org.postgresql.util.PSQLException
import java.util.concurrent.TimeUnit

import fr.acinq.eclair.wire.{ChannelAnnouncement, Color}
import slick.jdbc.PostgresProfile
import scodec.bits.ByteVector
import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.PublicKey

import scala.util.Try


object Tools {
  lazy val invalidPubKey: PublicKey = PublicKey.fromBin(ByteVector.fromValidHex("02" * 33), checkValid = false)

  def isPHC(ca: ChannelAnnouncement): Boolean = ca.bitcoinKey1 == invalidPubKey && ca.bitcoinKey2 == invalidPubKey && ca.bitcoinSignature1 == ByteVector64.Zeroes && ca.bitcoinSignature2 == ByteVector64.Zeroes

  def makeExpireAfterAccessCache(expiryMins: Int): CacheBuilder[AnyRef, AnyRef] = CacheBuilder.newBuilder.expireAfterAccess(expiryMins, TimeUnit.MINUTES)

  def makeExpireAfterWriteCache(expiryMins: Int): CacheBuilder[AnyRef, AnyRef] = CacheBuilder.newBuilder.expireAfterWrite(expiryMins, TimeUnit.MINUTES)

  def toMapBy[K, V](items: Iterable[V], mapper: V => K): Map[K, V] = items.map(item => mapper(item) -> item).toMap

  case object DuplicateShortId extends Throwable("Duplicate ShortId is not allowed here")

  abstract class DuplicateHandler[T] { me =>
    def execute(data: T): Try[Boolean] = Try(me insert data) recover {
      case dup: PSQLException if "23505" == dup.getSQLState => throw DuplicateShortId
      case otherError: Throwable => throw otherError
    }

    def insert(data: T): Boolean
  }

  // HC ids derivation

  def hostedNodesCombined(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector = {
    val pubkey1First: Boolean = LexicographicalOrdering.isLessThan(pubkey1, pubkey2)
    if (pubkey1First) pubkey1 ++ pubkey2 else pubkey2 ++ pubkey1
  }

  def hostedChanId(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector32 = {
    val nodesCombined = hostedNodesCombined(pubkey1, pubkey2)
    Crypto.sha256(nodesCombined)
  }

  def hostedShortChanId(pubkey1: ByteVector, pubkey2: ByteVector): ShortChannelId = {
    val stream = new ByteArrayInputStream(hostedNodesCombined(pubkey1, pubkey2).toArray)
    def getChunk: Long = Protocol.uint64(stream, ByteOrder.BIG_ENDIAN)
    val id = List.fill(8)(getChunk).foldLeft(Long.MaxValue)(_ % _)
    ShortChannelId(id)
  }
}

object Config {
  val config: Configuration = ConfigFactory parseFile new File(s"${System getProperty "user.dir"}/src/main/resources", "hc.conf")

  val db: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.relationalDb", config)

  implicit val colorReader: ValueReader[Color] = ValueReader.relative { source =>
    Color(source.getInt("r").toByte, source.getInt("g").toByte, source.getInt("b").toByte)
  }

  val vals: Vals = config.as[Vals]("config.vals")
}

case class BrandingData(logo: String, color: Color) {
  var brandingMessageOpt: Option[HostedChannelBranding] = None

  Try {
    val pngBytes = ByteVector view Files.readAllBytes(Paths get logo)
    val message = HostedChannelBranding(color, pngBytes)
    brandingMessageOpt = Some(message)
  }
}

case class Vals(feeBaseMsat: Long, feeProportionalMillionths: Long, cltvDeltaBlocks: Int, onChainRefundThresholdSat: Long,
                liabilityDeadlineBlockdays: Int, defaultCapacityMsat: Long, defaultClientBalanceMsat: Long, maxHtlcValueInFlightMsat: Long,
                htlcMinimumMsat: Long, maxAcceptedHtlcs: Int, branding: BrandingData) {

  val feeBase: MilliSatoshi = MilliSatoshi(feeBaseMsat)

  val cltvDelta: CltvExpiryDelta = CltvExpiryDelta(cltvDeltaBlocks)

  val onChainRefundThreshold: Satoshi = Satoshi(onChainRefundThresholdSat)

  val defaultCapacity: MilliSatoshi = MilliSatoshi(defaultCapacityMsat)

  val defaultClientBalance: MilliSatoshi = MilliSatoshi(defaultClientBalanceMsat)

  val maxHtlcValueInFlight: MilliSatoshi = MilliSatoshi(maxHtlcValueInFlightMsat)

  val htlcMinimum: MilliSatoshi = MilliSatoshi(htlcMinimumMsat)
}