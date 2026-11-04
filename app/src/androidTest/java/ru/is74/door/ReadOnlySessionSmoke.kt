package ru.is74.door

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.is74.door.data.Session
import ru.is74.door.data.SessionStore
import java.io.File

/** Optional local emulator setup. No credentials are bundled or sent by this test. */
@RunWith(AndroidJUnit4::class)
class ReadOnlySessionSmoke {
    @Test fun importTemporarySessionForReadOnlyPlayback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(context.filesDir,"smoke-session.json")
        assumeTrue("No optional local session supplied",source.exists())
        val json=try { JSONObject(source.readText()) } finally { source.delete() }
        val session=Session(json.getString("token"),json.getString("userId"),json.getString("profileId"),
            json.getString("deviceId"),json.optString("intercomToken").takeIf { it.isNotBlank() })
        val store=SessionStore(context)
        store.save(session)
        assertTrue(store.load()?.token==session.token)
    }
}
