package okay.http

import java.nio.charset.StandardCharsets.UTF_8

/** BYTES, compared by content, never changed: a binary body — an image, a file, a blob */
final class Bytes private (private val a: Array[Byte]) {
  def size: Int = a.length
  def isEmpty: Boolean = a.isEmpty
  def toArray: Array[Byte] = a.clone()
  /** the bytes as UTF-8 text (what is not UTF-8 replaced) */
  def text: String = new String(a, UTF_8)
  def slice(from: Int, until: Int): Bytes = new Bytes(a.slice(from, until))
  override def equals(o: Any): Boolean = o match {
    case b: Bytes => java.util.Arrays.equals(a, b.a)
    case _ => false
  }
  override def hashCode: Int = java.util.Arrays.hashCode(a)
  override def toString: String = s"Bytes(${a.length})"
}

object Bytes {
  val empty: Bytes = new Bytes(Array.emptyByteArray)
  def apply(a: Array[Byte]): Bytes = new Bytes(a.clone())
  def of(text: String): Bytes = new Bytes(text.getBytes(UTF_8))

  /** a body as it came, over the wire: text if it is UTF-8 through and through, the bytes themselves if not — so a
   * text body stays `body`, as ever, and nothing binary is lost */
  def body(raw: Array[Byte]): (String, Option[Bytes]) = {
    val text = new String(raw, UTF_8)
    if (java.util.Arrays.equals(text.getBytes(UTF_8), raw)) (text, None) else ("", Some(new Bytes(raw.clone())))
  }
}
