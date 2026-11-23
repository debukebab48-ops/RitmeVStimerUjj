package com.exp.rhythm

import android.net.VpnService
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Relay IPv4 ringan di Kotlin murni tanpa library eksternal.
 * Mendukung forward UDP (termasuk DNS) dan TCP dasar untuk keperluan testbed.
 * Wajib memanggil vpnService.protect(...) pada setiap socket keluar agar tidak loop.
 */
class LocalIpRelay(
    private val vpnService: VpnService,
    private val tunOutput: FileOutputStream,
    private val engine: RhythmEngine
) {

    private val ioExecutor = Executors.newCachedThreadPool()
    private val udpSockets = ConcurrentHashMap<String, DatagramSocket>()
    private val ipIdentCounter = AtomicInteger(1000)

    @Volatile
    private var running = true

    private val tunWriteLock = Any()

    fun handleIpPacket(rawPacket: ByteArray, length: Int) {
        if (!running || length < 20) return

        val firstByte = rawPacket[0].toInt() and 0xFF
        val version = firstByte ushr 4
        if (version != 4) return // Fokus IPv4 pada prototype awal

        val ihlBytes = (firstByte and 0x0F) * 4
        if (ihlBytes < 20 || length < ihlBytes) return

        val protocol = rawPacket[9].toInt() and 0xFF
        val srcIp = ipv4BytesToInt(rawPacket, 12)
        val dstIp = ipv4BytesToInt(rawPacket, 16)
        val dstAddress = intToInetAddress(dstIp)

        when (protocol) {
            17 -> handleUdp(rawPacket, ihlBytes, length, srcIp, dstIp, dstAddress)
            6 -> handleTcp(rawPacket, ihlBytes, length, srcIp, dstIp, dstAddress)
        }
    }

    private fun handleUdp(
        pkt: ByteArray,
        ipHeaderLen: Int,
        totalLen: Int,
        srcIp: Int,
        dstIp: Int,
        dstAddress: InetAddress
    ) {
        if (totalLen < ipHeaderLen + 8) return

        val srcPort = readUint16(pkt, ipHeaderLen)
        val dstPort = readUint16(pkt, ipHeaderLen + 2)
        val udpLen = readUint16(pkt, ipHeaderLen + 4)
        val payloadOffset = ipHeaderLen + 8
        val payloadLen = minOf(totalLen - payloadOffset, udpLen - 8)
        if (payloadLen <= 0 || payloadOffset + payloadLen > totalLen) return

        val key = "$srcIp:$srcPort->$dstIp:$dstPort"
        val socket = udpSockets.computeIfAbsent(key) {
            DatagramSocket().apply {
                soTimeout = 10_000
                if (!vpnService.protect(this)) {
                    close()
                    throw IllegalStateException("Gagal protect UDP socket")
                }
                startUdpReader(this, srcIp, srcPort, dstIp, dstPort)
            }
        }

        val outPacket = DatagramPacket(pkt, payloadOffset, payloadLen, dstAddress, dstPort)
        ioExecutor.execute {
            runCatching {
                val delayMs = engine.onTrafficCycle(payloadLen)
                if (delayMs > 0L) Thread.sleep(delayMs)
                socket.send(outPacket)
            }
        }
    }

    private fun startUdpReader(
        socket: DatagramSocket,
        clientIp: Int,
        clientPort: Int,
        remoteIp: Int,
        remotePort: Int
    ) {
        ioExecutor.execute {
            val recvBuf = ByteArray(4096)
            while (running && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(recvBuf, recvBuf.size)
                    socket.receive(packet)
                    val len = packet.length
                    if (len > 0) {
                        val delayMs = engine.onTrafficCycle(len)
                        if (delayMs > 0L) Thread.sleep(delayMs)

                        val ipPacket = buildIpv4UdpPacket(
                            srcIp = remoteIp,
                            dstIp = clientIp,
                            srcPort = remotePort,
                            dstPort = clientPort,
                            payload = packet.data,
                            payloadOffset = packet.offset,
                            payloadLen = len
                        )
                        writeToTun(ipPacket)
                    }
                } catch (_: Exception) {
                    if (!running || socket.isClosed) break
                }
            }
        }
    }

    private fun handleTcp(
        pkt: ByteArray,
        ipHeaderLen: Int,
        totalLen: Int,
        srcIp: Int,
        dstIp: Int,
        dstAddress: InetAddress
    ) {
        if (totalLen < ipHeaderLen + 20) return
        val tcpHeaderLen = ((pkt[ipHeaderLen + 12].toInt() and 0xF0) ushr 4) * 4
        if (tcpHeaderLen < 20 || totalLen < ipHeaderLen + tcpHeaderLen) return

        val srcPort = readUint16(pkt, ipHeaderLen)
        val dstPort = readUint16(pkt, ipHeaderLen + 2)
        val tcpFlags = pkt[ipHeaderLen + 13].toInt() and 0xFF
        val isSyn = (tcpFlags and 0x02) != 0
        val isAck = (tcpFlags and 0x10) != 0

        val payloadOffset = ipHeaderLen + tcpHeaderLen
        val payloadLen = totalLen - payloadOffset

        // Prototype relay ringan: saat ada SYN awal atau payload kecil, buka socket keluar
        // dan kirim payload awal jika ada. Untuk full TCP stream/HTTPS/video,
        // sambungkan ke user-space stack (seperti tun2socks) agar state TCP lengkap.
        if (isSyn || (payloadLen > 0 && !isAck)) {
            ioExecutor.execute {
                var sock: Socket? = null
                try {
                    sock = Socket()
                    if (!vpnService.protect(sock)) return@execute
                    sock.soTimeout = 5000
                    sock.connect(InetSocketAddress(dstAddress, dstPort), 4000)

                    if (payloadLen > 0) {
                        val delayMs = engine.onTrafficCycle(payloadLen)
                        if (delayMs > 0L) Thread.sleep(delayMs)
                        sock.getOutputStream().write(pkt, payloadOffset, payloadLen)
                        sock.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                    // Abaikan error koneksi percobaan di prototype ringan
                } finally {
                    runCatching { sock?.close() }
                }
            }
        }
    }

    private fun buildIpv4UdpPacket(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLen: Int
    ): ByteArray {
        val udpLen = 8 + payloadLen
        val totalLen = 20 + udpLen
        val buf = ByteBuffer.allocate(totalLen)

        // IPv4 Header (20 bytes)
        buf.put(0x45.toByte()) // Version 4, IHL 5
        buf.put(0x00.toByte()) // DSCP/ECN
        buf.putShort(totalLen.toShort())
        buf.putShort((ipIdentCounter.incrementAndGet() and 0xFFFF).toShort())
        buf.putShort(0x4000.toShort()) // Don't Fragment
        buf.put(64.toByte()) // TTL
        buf.put(17.toByte()) // Protocol UDP
        buf.putShort(0) // Checksum placeholder (offset 10)
        buf.putInt(srcIp)
        buf.putInt(dstIp)

        // Hitung IPv4 Header Checksum
        val ipBytes = buf.array()
        val ipChecksum = computeChecksum(ipBytes, 0, 20)
        buf.putShort(10, ipChecksum.toShort())

        // UDP Header (8 bytes)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // UDP checksum opsional untuk IPv4 (0 = tidak dihitung)

        // Payload
        buf.position(28)
        buf.put(payload, payloadOffset, payloadLen)

        return buf.array()
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(tunWriteLock) {
            if (!running) return
            runCatching {
                tunOutput.write(packet)
                tunOutput.flush()
            }
        }
    }

    fun close() {
        running = false
        udpSockets.values.forEach { runCatching { it.close() } }
        udpSockets.clear()
        ioExecutor.shutdownNow()
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun readUint16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun ipv4BytesToInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun intToInetAddress(ip: Int): InetAddress {
        val bytes = byteArrayOf(
            ((ip ushr 24) and 0xFF).toByte(),
            ((ip ushr 16) and 0xFF).toByte(),
            ((ip ushr 8) and 0xFF).toByte(),
            (ip and 0xFF).toByte()
        )
        return InetAddress.getByAddress(bytes)
    }
}
