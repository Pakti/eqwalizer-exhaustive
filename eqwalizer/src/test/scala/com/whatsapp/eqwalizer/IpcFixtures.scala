package com.whatsapp.eqwalizer

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** Deterministic lookup replies for Scala unit tests. This does not run ELP.
  * Ipc reads reply headers through Console.in and byte payloads through System.in.
  */
object IpcFixtures {
  def replies[A](responses: List[(String, Array[Byte])])(body: => A): A = synchronized {
    val headers = responses.map { case (name, bytes) => s"{\"$name\":{\"len\":${bytes.length}}}\n" }.mkString
    val previous = System.in
    val payload = new ByteArrayInputStream(responses.flatMap(_._2).toArray)
    val output = new ByteArrayOutputStream()
    System.setIn(payload)
    try
      Console.withIn(new ByteArrayInputStream(headers.getBytes(UTF_8))) {
        Console.withOut(output)(body)
      }
    finally System.setIn(previous)
  }
}
