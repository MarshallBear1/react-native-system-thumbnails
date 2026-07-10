package marshallbear1.reactnativesystemthumbnails.example

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/** Internal read-only provider used to exercise real content:// thumbnail input. */
class ThumbnailFixtureProvider : ContentProvider() {

  // The fixture is also a drawable for the source preview; openRawResource
  // intentionally streams its original packaged PNG bytes to ContentResolver.
  private val fixtureBytes: ByteArray by lazy {
    readFixtureBytes()
  }

  @SuppressLint("ResourceType")
  private fun readFixtureBytes(): ByteArray {
    return requireNotNull(context)
      .resources
      .openRawResource(R.drawable.thumbnail_source)
      .use { stream -> stream.readBytes() }
  }

  override fun onCreate(): Boolean = true

  override fun getType(uri: Uri): String? =
    if (isFixture(uri)) MIME_TYPE else null

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?
  ): Cursor {
    val columns = projection?.let { arrayOf(*it) } ?: DEFAULT_PROJECTION
    val cursor = MatrixCursor(columns, 1)
    if (!isFixture(uri)) {
      return cursor
    }

    val row = cursor.newRow()
    columns.forEach { column ->
      row.add(
        column,
        when (column) {
          BaseColumns._ID -> FIXTURE_ID
          OpenableColumns.DISPLAY_NAME -> DISPLAY_NAME
          OpenableColumns.SIZE -> fixtureBytes.size.toLong()
          MediaStore.MediaColumns.MIME_TYPE -> MIME_TYPE
          LAST_MODIFIED_COLUMN -> LAST_MODIFIED_VALUE
          else -> null
        }
      )
    }
    return cursor
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    if (!isFixture(uri)) {
      throw FileNotFoundException("Unknown fixture URI: $uri")
    }
    if (mode != "r") {
      throw FileNotFoundException("The thumbnail fixture is read-only.")
    }

    val pipe = ParcelFileDescriptor.createPipe()
    Thread(
      {
        runCatching {
          ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
            output.write(fixtureBytes)
          }
        }
      },
      "thumbnail-fixture-provider"
    ).apply {
      isDaemon = true
      start()
    }
    return pipe[0]
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? =
    throw UnsupportedOperationException("The thumbnail fixture is read-only.")

  override fun delete(
    uri: Uri,
    selection: String?,
    selectionArgs: Array<out String>?
  ): Int = throw UnsupportedOperationException("The thumbnail fixture is read-only.")

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?
  ): Int = throw UnsupportedOperationException("The thumbnail fixture is read-only.")

  private fun isFixture(uri: Uri): Boolean =
    uri.scheme == "content" &&
      uri.authority == "${requireNotNull(context).packageName}.fixtures" &&
      uri.pathSegments == listOf(FIXTURE_PATH)

  companion object {
    private const val FIXTURE_ID = 1L
    private const val FIXTURE_PATH = "thumbnail-source.png"
    private const val DISPLAY_NAME = "thumbnail-source.png"
    private const val MIME_TYPE = "image/png"
    private const val LAST_MODIFIED_COLUMN = "last_modified"
    private const val LAST_MODIFIED_VALUE = 1_720_569_600_000L
    private val DEFAULT_PROJECTION = arrayOf(
      BaseColumns._ID,
      OpenableColumns.DISPLAY_NAME,
      OpenableColumns.SIZE,
      MediaStore.MediaColumns.MIME_TYPE,
      LAST_MODIFIED_COLUMN
    )
  }
}
