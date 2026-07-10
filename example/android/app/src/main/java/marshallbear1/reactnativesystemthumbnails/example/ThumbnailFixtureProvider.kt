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
  private val imageFixture: Fixture by lazy {
    Fixture(
      id = IMAGE_FIXTURE_ID,
      path = IMAGE_FIXTURE_PATH,
      displayName = IMAGE_DISPLAY_NAME,
      mimeType = IMAGE_MIME_TYPE,
      bytes = readImageFixtureBytes()
    )
  }

  private val unknownFixture = Fixture(
    id = UNKNOWN_FIXTURE_ID,
    path = UNKNOWN_FIXTURE_PATH,
    displayName = UNKNOWN_DISPLAY_NAME,
    mimeType = UNKNOWN_MIME_TYPE,
    bytes = "RNST_UNKNOWN_FIXTURE_V1".toByteArray(Charsets.UTF_8)
  )

  private data class Fixture(
    val id: Long,
    val path: String,
    val displayName: String,
    val mimeType: String,
    val bytes: ByteArray
  )

  private fun fixtureFor(uri: Uri): Fixture? {
    if (
      uri.scheme != "content" ||
      uri.authority != "${requireNotNull(context).packageName}.fixtures"
    ) {
      return null
    }

    return when (uri.pathSegments.singleOrNull()) {
      IMAGE_FIXTURE_PATH -> imageFixture
      UNKNOWN_FIXTURE_PATH -> unknownFixture
      else -> null
    }
  }

  @SuppressLint("ResourceType")
  private fun readImageFixtureBytes(): ByteArray {
    return requireNotNull(context)
      .resources
      .openRawResource(R.drawable.thumbnail_source)
      .use { stream -> stream.readBytes() }
  }

  override fun onCreate(): Boolean = true

  override fun getType(uri: Uri): String? =
    fixtureFor(uri)?.mimeType

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?
  ): Cursor {
    val columns = projection?.let { arrayOf(*it) } ?: DEFAULT_PROJECTION
    val cursor = MatrixCursor(columns, 1)
    val fixture = fixtureFor(uri)
    if (fixture == null) {
      return cursor
    }

    val row = cursor.newRow()
    columns.forEach { column ->
      row.add(
        column,
        when (column) {
          BaseColumns._ID -> fixture.id
          OpenableColumns.DISPLAY_NAME -> fixture.displayName
          OpenableColumns.SIZE -> fixture.bytes.size.toLong()
          MediaStore.MediaColumns.MIME_TYPE -> fixture.mimeType
          LAST_MODIFIED_COLUMN -> LAST_MODIFIED_VALUE
          else -> null
        }
      )
    }
    return cursor
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    val fixture = fixtureFor(uri)
      ?: throw FileNotFoundException("Unknown fixture URI: $uri")
    if (mode != "r") {
      throw FileNotFoundException("The thumbnail fixture is read-only.")
    }

    val pipe = ParcelFileDescriptor.createPipe()
    Thread(
      {
        runCatching {
          ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
            output.write(fixture.bytes)
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

  companion object {
    private const val IMAGE_FIXTURE_ID = 1L
    private const val IMAGE_FIXTURE_PATH = "thumbnail-source.png"
    private const val IMAGE_DISPLAY_NAME = "thumbnail-source.png"
    private const val IMAGE_MIME_TYPE = "image/png"
    private const val UNKNOWN_FIXTURE_ID = 2L
    private const val UNKNOWN_FIXTURE_PATH = "unknown-source.rnstunknown"
    private const val UNKNOWN_DISPLAY_NAME = "unknown-source.rnstunknown"
    private const val UNKNOWN_MIME_TYPE = "application/x-rnst-unknown"
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
