package com.glint.player

import org.junit.Assert.assertEquals
import org.junit.Test

class UpnpTest {
    @Test fun denonRoot() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
<container childCount="10" searchable="0" id="inputs/" restricted="1" parentID="0">
<dc:title>Audio Inputs</dc:title>
<upnp:class>object.container.local.inputs</upnp:class>
</container>
<item id="v1" parentID="0"><dc:title>Holiday</dc:title><res protocolInfo="http-get:*:video/mp4:*" size="1234">http://192.168.1.5:8200/MediaItems/12.mp4</res></item>
</DIDL-Lite>"""
        val list = Upnp.parseDidl(didl)
        assertEquals(listOf("Audio Inputs", "Holiday.mp4"), list.map { it.name })
        assertEquals("c:inputs/", list[0].path)
        assertEquals("u:http://192.168.1.5:8200/MediaItems/12.mp4", list[1].path)
    }
}
