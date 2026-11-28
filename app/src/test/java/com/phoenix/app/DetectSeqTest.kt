package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Д2: из двух перекрывающихся определений голос записывает только запущенное позже. */
class DetectSeqTest {
    @Test fun isLatest() { assertTrue(DetectSeq.isLatest(5, 5)); assertFalse(DetectSeq.isLatest(4, 5)) }

    @Test fun onlySecondRunVotes() {
        val seq = AtomicLong(); val lock = Any(); val votes = ArrayList<String>()
        fun tryVote(name: String, my: Long) = synchronized(lock) { if (DetectSeq.isLatest(my, seq.get())) votes += name }
        val first = seq.incrementAndGet()   // первый замер начался
        val second = seq.incrementAndGet()  // второй начался, пока первый ещё шёл
        tryVote("first", first)             // первый дошёл до конца (отмена его не прервала)
        tryVote("second", second)
        assertEquals(listOf("second"), votes)
    }
}
