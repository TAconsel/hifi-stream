package com.hifistream.sender

/** The last [size] packets sent, by sequence number, for retransmission on request. */
class PacketHistory(private val size: Int, packetBytes: Int) {
    private val data = Array(size) { ByteArray(packetBytes) }
    private val lens = IntArray(size)
    private val seqs = IntArray(size) { -1 }
    private val lock = Any()

    @Volatile var resent = 0L

    fun put(seq: Int, src: ByteArray, len: Int) = synchronized(lock) {
        val i = seq % size
        System.arraycopy(src, 0, data[i], 0, len)
        lens[i] = len
        seqs[i] = seq and 0xFFFF
    }

    /** A private copy of the packet with this 16-bit sequence number, or null if it is gone. */
    fun get(seq16: Int): Pair<ByteArray, Int>? = synchronized(lock) {
        // The 16-bit wire sequence only identifies a slot when it is still in the history.
        for (i in 0 until size) {
            if (seqs[i] == seq16) return data[i].copyOf(lens[i]) to lens[i]
        }
        null
    }
}
