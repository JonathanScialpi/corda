/*
 * R3 Proprietary and Confidential
 *
 * Copyright (c) 2018 R3 Limited.  All rights reserved.
 *
 * The intellectual and technical concepts contained herein are proprietary to R3 and its suppliers and are protected by trade secret law.
 *
 * Distribution of this file or any portion thereof via any medium without the express permission of R3 is strictly prohibited.
 */

package net.corda.nodeapi.internal.protonwrapper.engine

import io.netty.buffer.ByteBuf
import io.netty.buffer.PooledByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.debug
import net.corda.nodeapi.internal.protonwrapper.messages.MessageStatus
import net.corda.nodeapi.internal.protonwrapper.messages.impl.ReceivedMessageImpl
import net.corda.nodeapi.internal.protonwrapper.messages.impl.SendableMessageImpl
import org.apache.qpid.proton.Proton
import org.apache.qpid.proton.amqp.Binary
import org.apache.qpid.proton.amqp.Symbol
import org.apache.qpid.proton.amqp.messaging.*
import org.apache.qpid.proton.amqp.messaging.Properties
import org.apache.qpid.proton.amqp.messaging.Target
import org.apache.qpid.proton.amqp.transaction.Coordinator
import org.apache.qpid.proton.amqp.transport.ErrorCondition
import org.apache.qpid.proton.amqp.transport.ReceiverSettleMode
import org.apache.qpid.proton.amqp.transport.SenderSettleMode
import org.apache.qpid.proton.engine.*
import org.apache.qpid.proton.message.Message
import org.apache.qpid.proton.message.ProtonJMessage
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*

/**
 * This ConnectionStateMachine class handles the events generated by the proton-j library to track
 * various logical connection, transport and link objects and to drive packet processing.
 * It is single threaded per physical SSL connection just like the proton-j library,
 * but this threading lock is managed by the EventProcessor class that calls this.
 * It ultimately posts application packets to/from from the netty transport pipeline.
 */
internal class ConnectionStateMachine(serverMode: Boolean,
                                      collector: Collector,
                                      private val localLegalName: String,
                                      private val remoteLegalName: String,
                                      userName: String?,
                                      password: String?) : BaseHandler() {
    companion object {
        private const val IDLE_TIMEOUT = 10000
    }

    val connection: Connection
    private val log = LoggerFactory.getLogger(localLegalName)
    private val transport: Transport
    private val id = UUID.randomUUID().toString()
    private var session: Session? = null
    private val messageQueues = mutableMapOf<String, LinkedList<SendableMessageImpl>>()
    private val unackedQueue = LinkedList<SendableMessageImpl>()
    private val receivers = mutableMapOf<String, Receiver>()
    private val senders = mutableMapOf<String, Sender>()
    private var tagId: Int = 0

    init {
        connection = Engine.connection()
        connection.container = "CORDA:$id"
        transport = Engine.transport()
        transport.idleTimeout = IDLE_TIMEOUT
        transport.context = connection
        transport.setEmitFlowEventOnSend(true)
        connection.collect(collector)
        val sasl = transport.sasl()
        if (userName != null) {
            //TODO This handshake is required for our queue permission logic in Artemis
            sasl.setMechanisms("PLAIN")
            if (serverMode) {
                sasl.server()
                sasl.done(Sasl.PN_SASL_OK)
            } else {
                sasl.plain(userName, password)
                sasl.client()
            }
        } else {
            sasl.setMechanisms("ANONYMOUS")
            if (serverMode) {
                sasl.server()
                sasl.done(Sasl.PN_SASL_OK)
            } else {
                sasl.client()
            }
        }
        transport.bind(connection)
        if (!serverMode) {
            connection.open()
        }
    }

    override fun onConnectionInit(event: Event) {
        val connection = event.connection
        log.debug { "Connection init $connection" }
    }

    override fun onConnectionLocalOpen(event: Event) {
        val connection = event.connection
        log.info("Connection local open $connection")
        val session = connection.session()
        session.open()
        this.session = session
        for (target in messageQueues.keys) {
            getSender(target)
        }
    }

    override fun onConnectionLocalClose(event: Event) {
        val connection = event.connection
        log.info("Connection local close $connection")
        connection.close()
        connection.free()
    }

    override fun onConnectionUnbound(event: Event) {
        if (event.connection == this.connection) {
            val channel = connection.context as? Channel
            if (channel != null) {
                if (channel.isActive) {
                    channel.close()
                }
            }
        }
    }

    override fun onConnectionFinal(event: Event) {
        val connection = event.connection
        log.debug { "Connection final $connection" }
        if (connection == this.connection) {
            this.connection.context = null
            for (queue in messageQueues.values) {
                // clear any dead messages
                while (true) {
                    val msg = queue.poll()
                    if (msg != null) {
                        msg.doComplete(MessageStatus.Rejected)
                        msg.release()
                    } else {
                        break
                    }
                }
            }
            messageQueues.clear()
            while (true) {
                val msg = unackedQueue.poll()
                if (msg != null) {
                    msg.doComplete(MessageStatus.Rejected)
                    msg.release()
                } else {
                    break
                }
            }
            // shouldn't happen, but close socket channel now if not already done
            val channel = connection.context as? Channel
            if (channel != null && channel.isActive) {
                channel.close()
            }
            // shouldn't happen, but cleanup any stranded items
            transport.context = null
            session = null
            receivers.clear()
            senders.clear()
        }
    }

    override fun onTransportHeadClosed(event: Event) {
        val transport = event.transport
        log.debug { "Transport Head Closed $transport" }
        transport.close_tail()
        onTransportInternal(transport)
    }

    override fun onTransportTailClosed(event: Event) {
        val transport = event.transport
        log.debug { "Transport Tail Closed $transport" }
        transport.close_head()
        onTransportInternal(transport)
    }

    override fun onTransportClosed(event: Event) {
        val transport = event.transport
        log.debug { "Transport Closed $transport" }
        if (transport == this.transport) {
            transport.unbind()
            transport.free()
            transport.context = null
        }
    }

    override fun onTransportError(event: Event) {
        val transport = event.transport
        log.info("Transport Error $transport")
        val condition = event.transport.condition
        if (condition != null) {
            log.info("Error: ${condition.description}")
        } else {
            log.info("Error (no description returned).")
        }
        onTransportInternal(transport)
    }

    override fun onTransport(event: Event) {
        val transport = event.transport
        log.debug { "Transport $transport" }
        onTransportInternal(transport)
    }

    private fun onTransportInternal(transport: Transport) {
        if (!transport.isClosed) {
            val pending = transport.pending() // Note this drives frame generation, which the susbsequent writes push to the socket
            if (pending > 0) {
                val connection = transport.context as? Connection
                val channel = connection?.context as? Channel
                channel?.writeAndFlush(transport)
            }
        }
    }

    override fun onSessionInit(event: Event) {
        val session = event.session
        log.debug { "Session init $session" }
    }

    override fun onSessionLocalOpen(event: Event) {
        val session = event.session
        log.debug { "Session local open $session" }
    }

    private fun getSender(target: String): Sender {
        if (!senders.containsKey(target)) {
            val sender = session!!.sender(UUID.randomUUID().toString())
            sender.source = Source().apply {
                address = target
                dynamic = false
                durable = TerminusDurability.NONE
            }
            sender.target = Target().apply {
                address = target
                dynamic = false
                durable = TerminusDurability.UNSETTLED_STATE
            }
            sender.senderSettleMode = SenderSettleMode.UNSETTLED
            sender.receiverSettleMode = ReceiverSettleMode.FIRST
            senders[target] = sender
            sender.open()
        }
        return senders[target]!!
    }

    override fun onSessionLocalClose(event: Event) {
        val session = event.session
        log.debug { "Session local close $session" }
        session.close()
        session.free()
    }

    override fun onSessionFinal(event: Event) {
        val session = event.session
        log.debug { "Session final $session" }
        if (session == this.session) {
            this.session = null
        }
    }

    override fun onLinkLocalOpen(event: Event) {
        val link = event.link
        if (link is Sender) {
            log.debug { "Sender Link local open ${link.name} ${link.source} ${link.target}" }
            senders[link.target.address] = link
            transmitMessages(link)
        }
        if (link is Receiver) {
            log.debug { "Receiver Link local open ${link.name} ${link.source} ${link.target}" }
            receivers[link.target.address] = link
        }
    }

    override fun onLinkRemoteOpen(event: Event) {
        val link = event.link
        if (link is Receiver) {
            if (link.remoteTarget is Coordinator) {
                log.debug { "Coordinator link received" }
            }
        }
    }

    override fun onLinkFinal(event: Event) {
        val link = event.link
        if (link is Sender) {
            log.debug { "Sender Link final ${link.name} ${link.source} ${link.target}" }
            senders.remove(link.target.address)
        }
        if (link is Receiver) {
            log.debug { "Receiver Link final ${link.name} ${link.source} ${link.target}" }
            receivers.remove(link.target.address)
        }
    }

    override fun onLinkFlow(event: Event) {
        val link = event.link
        if (link is Sender) {
            log.debug { "Sender Flow event: ${link.name} ${link.source} ${link.target}" }
            if (senders.containsKey(link.target.address)) {
                transmitMessages(link)
            }
        } else if (link is Receiver) {
            log.debug { "Receiver Flow event: ${link.name} ${link.source} ${link.target}" }
        }
    }

    fun processTransport() {
        onTransportInternal(transport)
    }

    private fun transmitMessages(sender: Sender) {
        val messageQueue = messageQueues.getOrPut(sender.target.address, { LinkedList() })
        while (sender.credit > 0) {
            log.debug { "Sender credit: ${sender.credit}" }
            val nextMessage = messageQueue.poll()
            if (nextMessage != null) {
                try {
                    val messageBuf = nextMessage.buf!!
                    val buf = ByteBuffer.allocate(4)
                    buf.putInt(tagId++)
                    val delivery = sender.delivery(buf.array())
                    delivery.context = nextMessage
                    sender.send(messageBuf.array(), messageBuf.arrayOffset() + messageBuf.readerIndex(), messageBuf.readableBytes())
                    nextMessage.status = MessageStatus.Sent
                    log.debug { "Put tag ${javax.xml.bind.DatatypeConverter.printHexBinary(delivery.tag)} on wire uuid: ${nextMessage.applicationProperties["_AMQ_DUPL_ID"]}" }
                    unackedQueue.offer(nextMessage)
                    sender.advance()
                } finally {
                    nextMessage.release()
                }
            } else {
                break
            }
        }
    }

    override fun onDelivery(event: Event) {
        val delivery = event.delivery
        log.debug { "Delivery $delivery" }
        val link = delivery.link
        if (link is Receiver) {
            if (delivery.isReadable && !delivery.isPartial) {
                val pending = delivery.pending()
                val amqpMessage = decodeAMQPMessage(pending, link)
                val payload = (amqpMessage.body as Data).value.array
                val connection = event.connection
                val channel = connection?.context as? Channel
                if (channel != null) {
                    val appProperties = HashMap(amqpMessage.applicationProperties.value)
                    appProperties["_AMQ_VALIDATED_USER"] = remoteLegalName
                    val localAddress = channel.localAddress() as InetSocketAddress
                    val remoteAddress = channel.remoteAddress() as InetSocketAddress
                    val receivedMessage = ReceivedMessageImpl(
                            payload,
                            link.source.address,
                            remoteLegalName,
                            NetworkHostAndPort(localAddress.hostString, localAddress.port),
                            localLegalName,
                            NetworkHostAndPort(remoteAddress.hostString, remoteAddress.port),
                            appProperties,
                            channel,
                            delivery)
                    log.debug { "Full message received uuid: ${appProperties["_AMQ_DUPL_ID"]}" }
                    channel.writeAndFlush(receivedMessage)
                    if (link.current() == delivery) {
                        link.advance()
                    }
                } else {
                    delivery.disposition(Rejected())
                    delivery.settle()
                }
            }
        } else if (link is Sender) {
            log.debug { "Sender delivery confirmed tag ${javax.xml.bind.DatatypeConverter.printHexBinary(delivery.tag)}" }
            val ok = delivery.remotelySettled() && delivery.remoteState == Accepted.getInstance()
            val sourceMessage = delivery.context as? SendableMessageImpl
            unackedQueue.remove(sourceMessage)
            sourceMessage?.doComplete(if (ok) MessageStatus.Acknowledged else MessageStatus.Rejected)
            delivery.settle()
        }
    }

    private fun encodeAMQPMessage(message: ProtonJMessage): ByteBuf {
        val buffer = PooledByteBufAllocator.DEFAULT.heapBuffer(1500)
        try {
            try {
                message.encode(NettyWritable(buffer))
                val bytes = ByteArray(buffer.writerIndex())
                buffer.readBytes(bytes)
                return Unpooled.wrappedBuffer(bytes)
            } catch (ex: Exception) {
                log.error("Unable to encode message as AMQP packet", ex)
                throw ex
            }
        } finally {
            buffer.release()
        }
    }

    private fun encodePayloadBytes(msg: SendableMessageImpl): ByteBuf {
        val message = Proton.message() as ProtonJMessage
        message.body = Data(Binary(msg.payload))
        message.isDurable = true
        message.properties = Properties()
        val appProperties = HashMap(msg.applicationProperties)
        //TODO We shouldn't have to do this, but Artemis Server doesn't set the header on AMQP packets.
        // Fortunately, when we are bridge to bridge/bridge to float we can authenticate links there.
        appProperties["_AMQ_VALIDATED_USER"] = localLegalName
        message.applicationProperties = ApplicationProperties(appProperties)
        return encodeAMQPMessage(message)
    }

    private fun decodeAMQPMessage(pending: Int, link: Receiver): Message {
        val msgBuf = PooledByteBufAllocator.DEFAULT.heapBuffer(pending)
        try {
            link.recv(NettyWritable(msgBuf))
            val amqpMessage = Proton.message()
            amqpMessage.decode(msgBuf.array(), msgBuf.arrayOffset() + msgBuf.readerIndex(), msgBuf.readableBytes())
            return amqpMessage
        } finally {
            msgBuf.release()
        }
    }

    fun transportWriteMessage(msg: SendableMessageImpl) {
        log.debug { "Queue application message write uuid: ${msg.applicationProperties["_AMQ_DUPL_ID"]} ${javax.xml.bind.DatatypeConverter.printHexBinary(msg.payload)}" }
        msg.buf = encodePayloadBytes(msg)
        val messageQueue = messageQueues.getOrPut(msg.topic, { LinkedList() })
        messageQueue.offer(msg)
        if (session != null) {
            val sender = getSender(msg.topic)
            transmitMessages(sender)
        }
    }

    fun transportProcessInput(msg: ByteBuf) {
        val source = msg.nioBuffer()
        try {
            do {
                val buffer = transport.inputBuffer
                val limit = Math.min(buffer.remaining(), source.remaining())
                val duplicate = source.duplicate()
                duplicate.limit(source.position() + limit)
                buffer.put(duplicate)
                transport.processInput().checkIsOk()
                source.position(source.position() + limit)
            } while (source.hasRemaining())
        } catch (ex: Exception) {
            val condition = ErrorCondition()
            condition.condition = Symbol.getSymbol("proton:io")
            condition.description = ex.message
            transport.condition = condition
            transport.close_tail()
            transport.pop(Math.max(0, transport.pending())) // Force generation of TRANSPORT_HEAD_CLOSE (not in C code)
        }
    }

    fun transportProcessOutput(ctx: ChannelHandlerContext) {
        try {
            var done = false
            while (!done) {
                val toWrite = transport.outputBuffer
                if (toWrite != null && toWrite.hasRemaining()) {
                    val outbound = ctx.alloc().buffer(toWrite.remaining())
                    outbound.writeBytes(toWrite)
                    ctx.write(outbound)
                    transport.outputConsumed()
                } else {
                    done = true
                }
            }
            ctx.flush()
        } catch (ex: Exception) {
            val condition = ErrorCondition()
            condition.condition = Symbol.getSymbol("proton:io")
            condition.description = ex.message
            transport.condition = condition
            transport.close_head()
            transport.pop(Math.max(0, transport.pending())) // Force generation of TRANSPORT_HEAD_CLOSE (not in C code)
        }
    }
}