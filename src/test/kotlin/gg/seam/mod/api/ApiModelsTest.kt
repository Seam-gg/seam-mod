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

    // ── The reporter (MCO-532) ─────────────────────────────────────────────────

    @Test
    fun `reporter tags carry the containers and what is worth reporting from them`() {
        val decoded = json.decodeFromString(
            ReporterTagsResponse.serializer(),
            """
            {
              "world_id": 3,
              "containers": [
                {"id":42,"project_id":7,"dimension":"minecraft:overworld","x":1,"y":64,"z":2,
                 "group_key":"1,64,2","kind":"chest","tagged_at":"2026-09-08T07:30:00Z","state":"ok"}
              ],
              "items_of_interest": [
                {"project_id":7,"item_ids":["minecraft:hopper","minecraft:iron_ingot"]}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(3, decoded.worldId)
        assertEquals(42L, decoded.containers.single().id)
        val interest = decoded.itemsOfInterest.single()
        assertEquals(7, interest.projectId)
        // The target the build asks for, and the plan item you actually go and mine.
        assertEquals(listOf("minecraft:hopper", "minecraft:iron_ingot"), interest.itemIds)
    }

    @Test
    fun `a contents push encodes as an absolute set for the containers it names`() {
        val encoded = json.encodeToString(
            ReporterContentsRequest.serializer(),
            ReporterContentsRequest(
                sweptAt = "2026-09-08T10:00:00Z",
                reporterVersion = "0.3.0+1.21.11",
                containers = listOf(
                    ReportedContainerDto(
                        id = 42,
                        state = "ok",
                        seenAt = "2026-09-08T10:00:00Z",
                        items = listOf(ReportedItemDto("minecraft:iron_ingot", 12)),
                    ),
                ),
            ),
        )

        // world_id is omitted on a dedicated server — the reporter token already fixes the world.
        assertTrue(!encoded.contains("world_id"), "world_id should be absent when null: $encoded")
        assertTrue(encoded.contains(""""swept_at":"2026-09-08T10:00:00Z""""))
        assertTrue(encoded.contains(""""item_id":"minecraft:iron_ingot","count":12"""))
    }

    @Test
    fun `singleplayer names the world, because the player token does not fix one`() {
        val encoded = json.encodeToString(
            ReporterContentsRequest.serializer(),
            ReporterContentsRequest(worldId = 3, containers = emptyList()),
        )

        assertTrue(encoded.contains(""""world_id":3"""))
        // An empty push is the heartbeat; there is no separate endpoint for it. This codec omits
        // defaults, so the empty list is simply absent from the wire — and the webapp's own model
        // defaults `containers` to empty, so an omitted list and an explicit `[]` mean the same
        // thing. What must survive is the round trip.
        assertTrue(!encoded.contains("containers"), "an empty list is omitted, not sent: $encoded")
        val roundTripped = json.decodeFromString(ReporterContentsRequest.serializer(), encoded)
        assertEquals(3, roundTripped.worldId)
        assertTrue(roundTripped.containers.isEmpty())
    }

    @Test
    fun `an unreadable container carries no items, so its stock stops counting`() {
        val encoded = json.encodeToString(
            ReportedContainerDto.serializer(),
            ReportedContainerDto(id = 9, state = "missing", seenAt = "2026-09-08T10:05:00Z"),
        )

        assertTrue(encoded.contains(""""state":"missing""""))
        // The state is what makes the webapp drop this container's stock; carrying no items is the
        // consequence, and an omitted empty list decodes back to exactly that.
        val roundTripped = json.decodeFromString(ReportedContainerDto.serializer(), encoded)
        assertEquals("missing", roundTripped.state)
        assertTrue(roundTripped.items.isEmpty())
    }

    @Test
    fun `a storage count decodes, and measured is not collected`() {
        val rows = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(WorldStorageDto.serializer()),
            """[{"project_id":7,"item_id":"minecraft:iron_ingot","measured":3200000000,
                 "container_count":4,"oldest_seen_at":"2026-09-08T09:58:00Z"}]""".trimIndent(),
        )

        val row = rows.single()
        assertEquals(3_200_000_000L, row.measured, "measured is a Long, like plan quantities")
        assertEquals(4, row.containerCount)
        assertEquals("2026-09-08T09:58:00Z", row.oldestSeenAt)
    }

    @Test
    fun `a world with no reading yet decodes with a null oldest_seen_at`() {
        val row = json.decodeFromString(
            WorldStorageDto.serializer(),
            """{"project_id":7,"item_id":"minecraft:iron_ingot","measured":0,"container_count":0,"oldest_seen_at":null}""",
        )

        assertEquals(0L, row.measured)
        assertNull(row.oldestSeenAt)
    }
}
