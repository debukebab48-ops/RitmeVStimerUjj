package com.exp.rhythm

import android.net.VpnService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local SOCKS5 proxy (no-auth, IPv4 & domain) untuk menerima aliran dari tun2socks,
 * menerapkan ritme/metrik dari RhythmEngine, dan melindungi socket keluar lewat VpnService.protect().
 */
class LocalSocks5Server(
    private val vpnService: VpnService,
    private val engine: RhythmEngine,
    private val listenHost: String = "127.0.0.1",
    private val listenPort: Int = 1080
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()

    fun start() {
        if (running.getAndSet(true)) return

        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(listenHost, listenPort), 128)
        serverSocket = ss

        acceptExecutor.execute {
            while (running.get()) {
                try {
                    val client = ss.accept()
                    clientExecutor.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000
            val inp = DataInputStream(sock.getInputStream())
            val out = DataOutputStream(sock.getOutputStream())

            // 1. SOCKS5 Greeting: [VER=5][NMETHODS][METHODS...]
            val ver = inp.readUnsignedByte()
            if (ver != 5) return
            val nMethods = inp.readUnsignedByte()
            val methods = ByteArray(nMethods)
            inp.readFully(methods)

            // Pilih metode 0x00 (NO AUTHENTICATION REQUIRED)
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()

            // 2. SOCKS5 Request: [VER=5][CMD][RSV=0][ATYP][DST.ADDR][DST.PORT]
            val reqVer = inp.readUnsignedByte()
            if (reqVer != 5) return
            val cmd = inp.readUnsignedByte()
            inp.readUnsignedByte() // RSV
            val atyp = inp.readUnsignedByte()

            val targetAddress = parseTargetAddress(inp, atyp) ?: run {
                sendReply(out, 0x08) // Address type not supported
                return
            }
            val targetPort = inp.readUnsignedShort()

            when (cmd) {
                0x01 -> handleTcpConnect(sock, inp, out, targetAddress, targetPort)
                0x03 -> handleUdpAssociate(sock, inp, out)
                else -> sendReply(out, 0x07) // Command not supported
            }
        }
    }

    private fun parseTargetAddress(inp: DataInputStream, atyp: Int): InetAddress? {
        return when (atyp) {
            0x01 -> { // IPv4 (4 bytes)
                val ipBytes = ByteArray(4)
                inp.readFully(ipBytes)
                InetAddress.getByAddress(ipBytes)
            }
            0x03 -> { // Domain name: [LEN][DOMAIN]
                val len = inp.readUnsignedByte()
                val domainBytes = ByteArray(len)
                inp.readFully(domainBytes)
                InetAddress.getByName(String(domainBytes, Charsets.UTF_8))
            }
            0x04 -> { // IPv6 (16 bytes) — skip di prototype awal atau dukung sesuai kebutuhan
                val ipBytes = ByteArray(16)
                inp.readFully(ipBytes)
                runCatching { InetAddress.getByAddress(ipBytes) }.getOrNull()
            }
            else -> null
        }
    }

    private fun handleTcpConnect(
        clientSock: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        targetAddress: InetAddress,
        targetPort: Int
    ) {
        val remoteSock = Socket()
        try {
            if (!vpnService.protect(remoteSock)) {
                sendReply(DataOutputStream(clientOut), 0x01)
                remoteSock.close()
                return
            }
            remoteSock.tcpNoDelay = true
            remoteSock.soTimeout = 30_000
            remoteSock.connect(InetSocketAddress(targetAddress, targetPort), 10_000)

            // Reply sukses: BND.ADDR = 0.0.0.0, BND.PORT = 0
            sendReply(DataOutputStream(clientOut), 0x00)

            // Relay dua arah: uplink (client -> remote) dan downlink (remote -> client)
            val upThread = Thread({
                pipeWithRhythm(clientIn, remoteSock.getOutputStream(), applyRhythm = true)
            }, "SocksUp-${targetAddress.hostAddress}:$targetPort")

            val downThread = Thread({
                pipeWithRhythm(remoteSock.getInputStream(), clientOut, applyRhythm = true)
            }, "SocksDown-${targetAddress.hostAddress}:$targetPort")

            upThread.start()
            downThread.start()
            upThread.join()
            downThread.join()
        } catch (_: Exception) {
            runCatching { sendReply(DataOutputStream(clientOut), 0x05) } // Connection refused
        } finally {
            runCatching { remoteSock.close() }
            runCatching { clientSock.close() }
        }
    }

    private fun handleUdpAssociate(
        clientSock: Socket,
        clientIn: InputStream,
        clientOut: OutputStream
    ) {
        var relayUdp: DatagramSocket? = null
        try {
            relayUdp = DatagramSocket(0, InetAddress.getByName(listenHost))
            relayUdp.soTimeout = 15_000

            if (!vpnService.protect(relayUdp)) {
                sendReply(DataOutputStream(clientOut), 0x01)
                return
            }

            // Kirim alamat & port UDP relay lokal ke tun2socks
            val boundPort = relayUdp.localPort
            val out = DataOutputStream(clientOut)
            out.writeByte(0x05) // VER
            out.writeByte(0x00) // REP = succeeded
            out.writeByte(0x00) // RSV
            out.writeByte(0x01) // ATYP = IPv4
            out.write(InetAddress.getByName(listenHost).address)
            out.writeShort(boundPort)
            out.flush()

            var clientUdpAddr: InetAddress? = null
            var clientUdpPort = -1
            val buf = ByteArray(4096)

            // Selama koneksi TCP kontrol SOCKS5 masih terbuka, teruskan datagram UDP
            while (running.get() && !clientSock.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    relayUdp.receive(pkt)

                    if (pkt.address.hostAddress == listenHost &&
                        (clientUdpPort == -1 || pkt.port == clientUdpPort)
                    ) {
                        clientUdpAddr = pkt.address
                        clientUdpPort = pkt.port

                        // Parse SOCKS5 UDP header: [RSV(2)][FRAG(1)][ATYP(1)][DST.ADDR][DST.PORT][DATA]
                        val parsed = parseSocks5UdpPacket(pkt.data, pkt.offset, pkt.length) ?: continue
                        val delayMs = engine.onTrafficCycle(parsed.payloadLen)
                        if (delayMs > 0L) Thread.sleep(delayMs)

                        val upstream = DatagramPacket(
                            parsed.payload,
                            parsed.payloadOffset,
                            parsed.payloadLen,
                            parsed.dstAddress,
                            parsed.dstPort
                        )
                        relayUdp.send(upstream)
                    } else if (clientUdpAddr != null && clientUdpPort != -1) {
                        // Balasan dari tujuan luar -> bungkus header SOCKS5 UDP lalu kirim balik ke tun2socks
                        val delayMs = engine.onTrafficCycle(pkt.length)
                        if (delayMs > 0L) Thread.sleep(delayMs)

                        val wrapped = wrapSocks5UdpPacket(
                            srcAddress = pkt.address,
                            srcPort = pkt.port,
                            payload = pkt.data,
                            payloadOffset = pkt.offset,
                            payloadLen = pkt.length
                        )
                        val replyPkt = DatagramPacket(wrapped, wrapped.size, clientUdpAddr, clientUdpPort)
                        relayUdp.send(replyPkt)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    if (clientIn.available() < 0) break
                }
            }
        } catch (_: Exception) {
            // Selesai atau terputus
        } finally {
            runCatching { relayUdp?.close() }
            runCatching { clientSock.close() }
        }
    }

    private fun pipeWithRhythm(src: InputStream, dst: OutputStream, applyRhythm: Boolean) {
        val buf = ByteArray(8192)
        try {
            while (running.get()) {
                val read = src.read(buf)
                if (read <= 0) break

                if (applyRhythm) {
                    val delayMs = engine.onTrafficCycle(read)
                    if (delayMs > 0L) {
                        Thread.sleep(delayMs)
                    }
                }

                dst.write(buf, 0, read)
                dst.flush()
            }
        } catch (_: Exception) {
            // Stream ditutup atau timeout
        }
    }

    private fun sendReply(out: DataOutputStream, rep: Int) {
        runCatching {
            out.writeByte(0x05) // VER
            out.writeByte(rep)  // REP
            out.writeByte(0x00) // RSV
            out.writeByte(0x01) // ATYP IPv4
            out.write(ByteArray(4)) // 0.0.0.0
            out.writeShort(0)   // Port 0
            out.flush()
        }
    }

    private data class ParsedUdp(
        val dstAddress: InetAddress,
        val dstPort: Int,
        val payload: ByteArray,
        val payloadOffset: Int,
        val payloadLen: Int
    )

    private fun parseSocks5UdpPacket(data: ByteArray, offset: Int, length: Int): ParsedUdp? {
        if (length < 10) return null
        val frag = data[offset + 2].toInt() and 0xFF
        if (frag != 0) return null // Fragmentasi tidak didukung di prototype awal

        val atyp = data[offset + 3].toInt() and 0xFF
        var pos = offset + 4

        val dstAddr = when (atyp) {
            0x01 -> {
                if (offset + length < pos + 6) return null
                val b = data.copyOfRange(pos, pos + 4)
                pos += 4
                InetAddress.getByAddress(b)
            }
            0x03 -> {
                if (offset + length < pos + 1) return null
                val len = data[pos].toInt() and 0xFF
                pos += 1
                if (offset + length < pos + len + 2) return null
                val host = String(data, pos, len, Charsets.UTF_8)
                pos += len
                InetAddress.getByName(host)
            }
            else -> return null
        }

        val port = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2

        val payloadLen = (offset + length) - pos
        if (payloadLen <= 0) return null

        return ParsedUdp(
            dstAddress = dstAddr,
            dstPort = port,
            payload = data,
            payloadOffset = pos,
            payloadLen = payloadLen
        )
    }

    private fun wrapSocks5UdpPacket(
        srcAddress: InetAddress,
        srcPort: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLen: Int
    ): ByteArray {
        val addrBytes = srcAddress.address
        val isIpv6 = addrBytes.size == 16
        val atyp: Byte = if (isIpv6) 0x04 else 0x01
        val headerLen = 4 + addrBytes.size + 2
        val out = ByteArray(headerLen + payloadLen)

        out[0] = 0x00 // RSV
        out[1] = 0x00 // RSV
        out[2] = 0x00 // FRAG
        out[3] = atyp

        System.arraycopy(addrBytes, 0, out, 4, addrBytes.size)
        val portOffset = 4 + addrBytes.size
        out[portOffset] = ((srcPort ushr 8) and 0xFF).toByte()
        out[portOffset + 1] = (srcPort and 0xFF).toByte()

        System.arraycopy(payload, payloadOffset, out, portOffset + 2, payloadLen)
        return out
    }
}
