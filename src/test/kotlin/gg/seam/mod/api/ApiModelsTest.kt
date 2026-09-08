package gg.seam.mod.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests against the exact JSON `mc-org`'s `app.mcorg.api.ApiDtos` produces. If the webapp
 * changes a field name these fail — which is the point: the two files are one contract in two repos.
 */
class ApiModelsTest {

    private val json = SeamJson.json

    @Test
    fun `device code response decodes the snake_case wire format`() {
        val decoded = json.decodeFromString(
            DeviceCodeResponse.serializer(),
            """
            {
              "device_code": "abc123",
              "user_code": "WXYZ-4242",
              "verification_uri": "https://app.seam.gg/link",
              "expires_in": 600,
              "interval": 5
            }
            """.trimIndent(),
        )

        assertEquals("abc123", decoded.deviceCode)
        assertEquals("WXYZ-4242", decoded.userCode)
        assertEquals("https://app.seam.gg/link", decoded.verificationUri)
        assertEquals(600L, decoded.expiresIn)
        assertEquals(5, decoded.interval)
    }

    @Test
    fun `poll success carries the bearer token and username`() {
        val decoded = json.decodeFromString(
            PollSuccessResponse.serializer(),
            """{"access_token":"tok","token_type":"Bearer","username":"Even"}""",
        )

        assertEquals("tok", decoded.accessToken)
        assertEquals("Even", decoded.username)
    }

    @Test
    fun `an RFC 8628 pending body decodes as a plain error`() {
        val decoded = json.decodeFromString(ApiErrorResponse.serializer(), """{"error":"authorization_pending"}""")

        assertEquals("authorization_pending", decoded.error)
        assertNull(decoded.message)
    }

    @Test
    fun `a project decodes with its resources and tasks`() {
        val projects = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ProjectDto.serializer()),
            """
            [{
              "id": 7,
              "name": "Iron Farm",
              "stage": "GATHERING",
              "state": "IN_PROGRESS",
              "resources": [
                {"item_id":"minecraft:iron_ingot","name":"Iron Ingot","required":320,"collected":64,"source_type":"MINED"},
                {"item_id":"minecraft:glass","name":"Glass","required":64,"collected":0}
              ],
              "tasks": [{"id":1,"name":"Dig out the platform","completed":true}]
            }]
            """.trimIndent(),
        )

        val project = projects.single()
        assertEquals(7, project.id)
        assertEquals("GATHERING", project.stage)
        assertEquals(2, project.resources.size)
        assertEquals("minecraft:iron_ingot", project.resources[0].itemId)
        assertEquals(320, project.resources[0].required)
        // source_type is nullable on the wire and omitted here.
        assertNull(project.resources[1].sourceType)
        assertTrue(project.tasks.single().completed)
    }

    @Test
    fun `unknown server fields are tolerated`() {
        val world = json.decodeFromString(
            WorldDto.serializer(),
            """{"id":1,"name":"Survival","description":"d","version":"1_21","total_projects":3,"completed_projects":1,"future_field":"whatever"}""",
        )

        assertEquals(1, world.id)
        assertEquals("Survival", world.name)
        assertEquals(3, world.totalProjects)
    }

    @Test
    fun `sync requests are encoded in the shape the API expects`() {
        val encoded = json.encodeToString(
            SyncRequest.serializer(),
            SyncRequest(listOf(SyncResourceItem("minecraft:iron_ingot", 128))),
        )

        assertEquals("""{"resources":[{"item_id":"minecraft:iron_ingot","collected":128}]}""", encoded)
    }

    @Test
    fun `task updates are encoded as a bare completed flag`() {
        assertEquals(
            """{"completed":true}""",
            json.encodeToString(TaskUpdateRequest.serializer(), TaskUpdateRequest(completed = true)),
        )
    }

    // ── Container tags (MCO-530) ───────────────────────────────────────────────

    @Test
    fun `a container tag decodes the snake_case wire format`() {
        val decoded = json.decodeFromString(
            ContainerTagsResponse.serializer(),
            """
            {
              "containers": [
                {
                  "id": 42,
                  "project_id": 7,
                  "dimension": "minecraft:overworld",
                  "x": 10, "y": 64, "z": -20,
                  "group_key": "10,64,-20",
                  "kind": "barrel",
                  "tagged_by": "Even",
                  "tagged_at": "2026-09-08T07:30:00Z",
                  "last_seen_at": "2026-09-08T07:31:00Z",
                  "state": "ok"
                }
              ]
            }
            """.trimIndent(),
        )

        val tag = decoded.containers.single()
        assertEquals(42L, tag.id)
        assertEquals(7, tag.projectId)
        assertEquals("minecraft:overworld", tag.dimension)
        assertEquals(-20, tag.z)
        assertEquals("10,64,-20", tag.groupKey)
        assertEquals("barrel", tag.kind)
        assertEquals("Even", tag.taggedBy)
        assertEquals("ok", tag.state)
    }

    @Test
    fun `a tag nothing has swept yet decodes as unreadable with no last_seen_at`() {
        val tag = json.decodeFromString(
            ContainerTagDto.serializer(),
            """
            {"id":1,"project_id":2,"dimension":"minecraft:overworld","x":0,"y":0,"z":0,
             "group_key":"0,0,0","kind":"chest","tagged_by":null,
             "tagged_at":"2026-09-08T07:30:00Z","last_seen_at":null,"state":"unreadable"}
            """.trimIndent(),
        )

        // Both fields belong to the server half; until it has read the position the honest answer
        // is "nothing has looked at this yet", not a count of zero.
        assertEquals("unreadable", tag.state)
        assertNull(tag.lastSeenAt)
        assertNull(tag.taggedBy)
    }

    @Test
    fun `tagging a single container omits group_key so the server defaults it to the position`() {
        val encoded = json.encodeToString(
            ContainerTagRequest.serializer(),
            ContainerTagRequest(
                dimension = "minecraft:overworld",
                x = 5, y = 70, z = -8,
                projectId = 3,
                kind = "chest",
            ),
        )

        assertEquals(
            """{"dimension":"minecraft:overworld","x":5,"y":70,"z":-8,"project_id":3,"kind":"chest"}""",
            encoded,
        )
    }

    @Test
    fun `both halves of a double chest are tagged with one explicit group key`() {
        val lower = json.encodeToString(
            ContainerTagRequest.serializer(),
            ContainerTagRequest("minecraft:overworld", 4, 64, 4, 3, "chest", groupKey = "4,64,4"),
        )
        val upper = json.encodeToString(
            ContainerTagRequest.serializer(),
            ContainerTagRequest("minecraft:overworld", 5, 64, 4, 3, "chest", groupKey = "4,64,4"),
        )

        assertTrue(lower.contains(""""group_key":"4,64,4""""))
        assertTrue(upper.contains(""""group_key":"4,64,4""""))
    }
}
