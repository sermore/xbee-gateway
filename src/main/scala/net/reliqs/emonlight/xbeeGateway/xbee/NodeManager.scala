package net.reliqs.emonlight.xbeeGateway.xbee

import com.digi.xbee.api.exceptions.XBeeException
import com.typesafe.scalalogging.LazyLogging
import com.digi.xbee.api.RemoteXBeeDevice
import net.reliqs.emonlight.xbeeGateway.Node

trait NodeManager { this: NodeFactoring with LazyLogging =>

  // FIXME make private
  val activeNodes = collection.mutable.Map[String, XbeeNode]()

  def findActiveNode(addr: String, name: String = ""): Option[XbeeNode] =
    (if (addr != null && addr.nonEmpty) activeNodes.get(addr)
    else activeNodes.values find (n => name != null && name.nonEmpty && n.node.name == name))

  def createActiveNode(addr: String, d: RemoteXBeeDevice, n: Node, proc: Processor, dsp: Dispatcher): Option[XbeeNode] = {
    logger.info(s"xbeeNode creation for device $d and node $n address $addr")
    assert(!activeNodes.contains(addr))
    try {
      val xn = factory.create(addr, d, n, proc, dsp)
      activeNodes.put(addr, xn)
    } catch {
      case e: XBeeException =>
        logger.warn(s"xbeeNode creation aborted due to error", e); None
    }
  }

  def removeActiveNode(address: String): Option[XbeeNode] = activeNodes.remove(address)

}
