package net.reliqs.emonlight.xbeeGateway.xbee

import org.scalatest.WordSpec
import com.typesafe.scalalogging.LazyLogging
import com.digi.xbee.api.utils.ByteUtils
import com.digi.xbee.api.utils.HexUtils

class XbeeNodeExtSpec extends WordSpec {

  class MyTest extends XbeeNodeExt with LazyLogging {

  }

  val n = new MyTest()

  "A node" should {
    "parse DHT22 data correctly" in {
      val b = HexUtils.hexStringToByteArray("44BD8A00000000000000ED02B400EAA0")
      assert(b.length == 16)
      val q = n.parseDHT22Msg(b)
      assert(q.isDefined)
      assert(q.get == (-17014, 0x00EA.toDouble / 10.0, 0x02B4.toDouble / 10.0, 237))
    }
  }

}