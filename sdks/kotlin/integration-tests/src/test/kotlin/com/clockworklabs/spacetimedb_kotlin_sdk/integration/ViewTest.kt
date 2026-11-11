package com.clockworklabs.spacetimedb_kotlin_sdk.integration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import module_bindings.Note
import module_bindings.QueryBuilder
import module_bindings.db
import module_bindings.reducers
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewTest {

    @Test
    fun `parameterized view instances merge snapshots and deliver deletes`() = runBlocking {
        val watcher = connectToDb()
        val writer = connectToDb()
        val firstTag = "${marker()}'quoted"
        val secondTag = marker()
        val firstInserted = CompletableDeferred<Note>()
        val secondInserted = CompletableDeferred<Note>()
        val firstDeleted = CompletableDeferred<Unit>()
        val secondDeleted = CompletableDeferred<Unit>()
        val notes = mutableListOf<Note>()
        try {
            watcher.subscribeTo(QueryBuilder().notesByTag(firstTag).toSql())
            watcher.subscribeTo(QueryBuilder().notesByTag(secondTag).toSql())
            watcher.conn.db.notesByTag.onInsert { _, note ->
                if (note.tag == firstTag) firstInserted.complete(note)
                if (note.tag == secondTag) secondInserted.complete(note)
            }
            watcher.conn.db.notesByTag.onDelete { _, note ->
                if (note.tag == firstTag) firstDeleted.complete(Unit)
                if (note.tag == secondTag) secondDeleted.complete(Unit)
            }
            writer.conn.reducers.addNote(marker(), firstTag)
            writer.conn.reducers.addNote(marker(), secondTag)
            notes += withTimeout(DEFAULT_TIMEOUT_MS) { firstInserted.await() }
            notes += withTimeout(DEFAULT_TIMEOUT_MS) { secondInserted.await() }
            assertEquals(notes.map { it.id }.toSet(), watcher.conn.db.notesByTag.all().map { it.id }.toSet())

            val late = connectToDb()
            try {
                late.subscribeTo(QueryBuilder().notesByTag(firstTag).toSql())
                assertEquals(listOf(notes[0].id), late.conn.db.notesByTag.all().map { it.id })
            } finally {
                late.conn.disconnect()
            }

            notes.forEach { writer.conn.reducers.deleteNote(it.id) }
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firstDeleted.await()
                secondDeleted.await()
            }
            notes.clear()
            assertEquals(0, watcher.conn.db.notesByTag.count())
        } finally {
            notes.forEach { writer.conn.reducers.deleteNote(it.id) }
            watcher.conn.disconnect()
            writer.conn.disconnect()
        }
    }

    private suspend fun ConnectedClient.subscribeTo(query: String) {
        val applied = CompletableDeferred<Unit>()
        conn.subscriptionBuilder()
            .onApplied { _ -> applied.complete(Unit) }
            .onError { _, err -> applied.completeExceptionally(RuntimeException("$err")) }
            .subscribe(query)
        withTimeout(DEFAULT_TIMEOUT_MS) { applied.await() }
    }

    private fun marker(): String = "view-test-${UUID.randomUUID()}"

    @Test
    fun `sender view delivers only the caller's rows`() = runBlocking {
        val owner = connectToDb()
        val other = connectToDb()
        owner.subscribeTo("SELECT * FROM my_notes")
        other.subscribeTo("SELECT * FROM my_notes")

        val content = marker()
        val inserted = CompletableDeferred<Note>()
        val deleted = CompletableDeferred<Note>()
        var leakedToOther = false
        owner.conn.db.myNotes.onInsert { _, note -> if (note.content == content) inserted.complete(note) }
        owner.conn.db.myNotes.onDelete { _, note -> if (note.content == content) deleted.complete(note) }
        other.conn.db.myNotes.onInsert { _, note -> if (note.content == content) leakedToOther = true }

        owner.conn.reducers.addNote(content, "view-test")
        val note = withTimeout(DEFAULT_TIMEOUT_MS) { inserted.await() }
        assertEquals(owner.identity, note.owner)
        assertTrue(owner.conn.db.myNotes.all().all { it.owner == owner.identity }, "my_notes must only hold the caller's notes")

        // The other client's own insert commits after the owner's, so once it arrives
        // the other client has processed every update that could have carried the owner's note.
        val otherContent = marker()
        val otherInserted = CompletableDeferred<Note>()
        other.conn.db.myNotes.onInsert { _, n -> if (n.content == otherContent) otherInserted.complete(n) }
        other.conn.reducers.addNote(otherContent, "view-test")
        val otherNote = withTimeout(DEFAULT_TIMEOUT_MS) { otherInserted.await() }
        assertFalse(leakedToOther, "Another client's note must not appear in my_notes")
        assertTrue(other.conn.db.myNotes.all().none { it.content == content })

        owner.conn.reducers.deleteNote(note.id)
        withTimeout(DEFAULT_TIMEOUT_MS) { deleted.await() }
        assertTrue(owner.conn.db.myNotes.all().none { it.content == content }, "Deleted note should leave the view cache")

        // Wait for the delete to commit so no note outlives the test.
        val otherDeleted = CompletableDeferred<Unit>()
        other.conn.db.myNotes.onDelete { _, n -> if (n.id == otherNote.id) otherDeleted.complete(Unit) }
        other.conn.reducers.deleteNote(otherNote.id)
        withTimeout(DEFAULT_TIMEOUT_MS) { otherDeleted.await() }
        owner.conn.disconnect()
        other.conn.disconnect()
    }

    @Test
    fun `anonymous view with primary key delivers inserts updates and deletes`() = runBlocking {
        val watcher = connectToDb()
        val writer = connectToDb()
        watcher.subscribeTo("SELECT * FROM shared_notes")

        val content = marker()
        val edited = "$content-edited"
        val inserted = CompletableDeferred<Note>()
        val updated = CompletableDeferred<Pair<Note, Note>>()
        val deleted = CompletableDeferred<Note>()
        var deletedBeforeUpdate = false
        watcher.conn.db.sharedNotes.onInsert { _, note -> if (note.content == content) inserted.complete(note) }
        watcher.conn.db.sharedNotes.onUpdate { _, old, new -> if (old.content == content) updated.complete(old to new) }
        watcher.conn.db.sharedNotes.onDelete { _, note ->
            if (note.content == content) deletedBeforeUpdate = true
            if (note.content == edited) deleted.complete(note)
        }

        writer.conn.reducers.addNote(content, "shared")
        val note = withTimeout(DEFAULT_TIMEOUT_MS) { inserted.await() }
        assertEquals(writer.identity, note.owner)

        // A client that subscribes later receives the existing row in its initial snapshot.
        val late = connectToDb()
        late.subscribeTo("SELECT * FROM shared_notes")
        assertTrue(late.conn.db.sharedNotes.all().any { it.id == note.id }, "Initial view rows should include the shared note")
        late.conn.disconnect()

        writer.conn.reducers.updateNote(note.id, edited)
        val (old, new) = withTimeout(DEFAULT_TIMEOUT_MS) { updated.await() }
        assertEquals(note.id, old.id)
        assertEquals(note.id, new.id)
        assertEquals(edited, new.content)
        assertFalse(deletedBeforeUpdate, "An edit to a keyed view row should arrive as an update, not a delete")
        assertEquals(1, watcher.conn.db.sharedNotes.all().count { it.id == note.id })

        writer.conn.reducers.deleteNote(note.id)
        withTimeout(DEFAULT_TIMEOUT_MS) { deleted.await() }
        assertTrue(watcher.conn.db.sharedNotes.all().none { it.id == note.id })

        watcher.conn.disconnect()
        writer.conn.disconnect()
    }
}
