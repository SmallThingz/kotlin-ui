package smallthingz.ui.input

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAndroidInteropRequestsTest {
    @Test fun rebindingSameCallbackCreatesAnIndependentLease() {
        val requests = NativeAndroidInteropRequests()
        val delivered = mutableListOf<Boolean>()
        val callback: (Boolean) -> Unit = { delivered += it }
        val old = requests.bind(callback)
        val current = requests.bind(callback)
        old.close()
        requests.request(true)
        old.close()
        requests.request(false)
        assertEquals(listOf(true, false), delivered)
        current.close()
        current.close()
        requests.request(true)
        assertEquals(listOf(true, false), delivered)
        val latest = requests.bind(callback)
        old.close()
        current.close()
        requests.request(true)
        assertEquals(listOf(true, false, true), delivered)
        latest.close()
    }

    @Test fun replacedDistinctCallbackAndRetiredLeaseCannotReclaimDelivery() {
        val requests = NativeAndroidInteropRequests()
        val delivered = mutableListOf<String>()
        val old = requests.bind { delivered += "old:$it" }
        requests.request(true)
        val current = requests.bind { delivered += "current:$it" }
        old.close()
        old.close()
        requests.request(false)
        assertEquals(listOf("old:true", "current:false"), delivered)
        current.close()
        current.close()
        requests.request(true)
        assertEquals(listOf("old:true", "current:false"), delivered)
    }
}
