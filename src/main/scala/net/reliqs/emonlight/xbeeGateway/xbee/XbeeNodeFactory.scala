package net.reliqs.emonlight.xbeeGateway.xbee

import com.digi.xbee.api.RemoteXBeeDevice
import net.reliqs.emonlight.xbeeGateway.Node

trait XbeeNodeFactory {
  def create(address: String, d: RemoteXBeeDevice, n: Node, proc: Processor, dsp: Dispatcher): XbeeNode
}

trait NodeFactoring {

  class SimpleFactory extends XbeeNodeFactory {
    override def create(address: String, device: RemoteXBeeDevice, node: Node, proc: Processor, dsp: Dispatcher) = {
      new XbeeNode(address, device, node, proc, dsp)
    }
  }

  val factory = new SimpleFactory()
}
