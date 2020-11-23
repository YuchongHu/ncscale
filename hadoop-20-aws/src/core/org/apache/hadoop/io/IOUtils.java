/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.io;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.Checksum;

import org.apache.commons.logging.Log;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.IOThrottler;
import org.apache.hadoop.util.NativeCrc32;

/**
 * An utility class for I/O related functionality. 
 */
public class IOUtils {

  /**
   * Copies from one stream to another.
   * @param in InputStrem to read from
   * @param out OutputStream to write to
   * @param buffSize the size of the buffer 
   * @param close whether or not close the InputStream and 
   * @param throttler IO Throttler
   * OutputStream at the end. The streams are closed in the finally clause.  
   */
  public static void copyBytes(InputStream in, OutputStream out, int buffSize,
      boolean close, IOThrottler throttler) 
    throws IOException {

//    System.out.println("this copyBytes!!!!!~~");
    PrintStream ps = out instanceof PrintStream ? (PrintStream)out : null;
    byte buf[] = new byte[buffSize];
    try {
      if (throttler != null) {
        throttler.throttle((long) buffSize);
      }
      int bytesRead = in.read(buf);
//      System.out.println("IOUtils.copyBytes.bytesRead = " + bytesRead);
      while (bytesRead >= 0) {
        out.write(buf, 0, bytesRead);
        if ((ps != null) && ps.checkError()) {
          throw new IOException("Unable to write to output stream.");
        }
        if (throttler != null) {
          throttler.throttle((long) buffSize);
        }
        bytesRead = in.read(buf);
      }
    } finally {
      if(close) {
        out.close();
        in.close();
      }
    }
  }
  
  /**
   * Copies from one stream to another.
   * @param in InputStrem to read from
   * @param out OutputStream to write to
   * @param buffSize the size of the buffer 
   * @param close whether or not close the InputStream and 
   * OutputStream at the end. The streams are closed in the finally clause.  
   */
  public static void copyBytes(InputStream in, OutputStream out, int buffSize,
      boolean close) 
    throws IOException {
    copyBytes(in, out, buffSize, close, null);
  }

  /**
   * Copies from one stream to another and generate CRC checksum.
   * @param in InputStrem to read from
   * @param out OutputStream to write to
   * @param buffSize the size of the buffer 
   * @param close whether or not close the InputStream and 
   * @param throttler IO Throttler
   * OutputStream at the end. The streams are closed in the finally clause.  
   * @return CRC checksum
   */
  public static long copyBytesAndGenerateCRC(InputStream in, OutputStream out, 
      int buffSize, boolean close, IOThrottler throttler) 
    throws IOException {
    
    PrintStream ps = out instanceof PrintStream ? (PrintStream)out : null;
    byte buf[] = new byte[buffSize];
    Checksum sum = new NativeCrc32();
    sum.reset();
    try {
      if (throttler != null) {
        throttler.throttle((long) buffSize);
      }
      int bytesRead = in.read(buf);
      while (bytesRead >= 0) {
        sum.update(buf, 0, bytesRead);
        out.write(buf, 0, bytesRead);
        if ((ps != null) && ps.checkError()) {
          throw new IOException("Unable to write to output stream.");
        }
        if (throttler != null) {
          throttler.throttle((long) buffSize);
        }
        bytesRead = in.read(buf);
      }
    } finally {
      if(close) {
        out.close();
        in.close();
      }
    }
    return sum.getValue();
  }
  
  /**
   * Copies from one stream to another and generate CRC checksum.
   * @param in InputStrem to read from
   * @param out OutputStream to write to
   * @param buffSize the size of the buffer 
   * @param close whether or not close the InputStream and 
   * OutputStream at the end. The streams are closed in the finally clause.  
   * @return CRC checksum
   */
  public static long copyBytesAndGenerateCRC(InputStream in, OutputStream out, 
      int buffSize, boolean close) 
    throws IOException {
    return copyBytesAndGenerateCRC(in, out, buffSize, close, null);
  }

  /**
   * Copies from one stream to another. <strong>closes the input and output streams 
   * at the end</strong>.
   * @param in InputStrem to read from
   * @param out OutputStream to write to
   * @param conf the Configuration object 
   */
  public static void copyBytes(InputStream in, OutputStream out, Configuration conf)
    throws IOException {
    copyBytes(in, out, conf.getInt("io.file.buffer.size", 4096), true);
  }
  
  /**
   * Copies from one stream to another.
   * @param in InputStrem to read from
   * @param out OutputStream to write to
   * @param conf the Configuration object
   * @param close whether or not close the InputStream and 
   * OutputStream at the end. The streams are closed in the finally clause.
   */
  public static void copyBytes(InputStream in, OutputStream out, Configuration conf, boolean close)
    throws IOException {
    copyBytes(in, out, conf.getInt("io.file.buffer.size", 4096), close);
  }
  
  /**
   * Copies from one stream to another and generate CRC
   * @param in InputStrem to read from
   * @param out OutputStream to write to
   * @param conf the Configuration object
   * @param close whether or not close the InputStream and 
   * OutputStream at the end. The streams are closed in the finally clause.
   * @return the CRC checksum
   */
  public static long copyBytesAndGenerateCRC(InputStream in, OutputStream out,
      Configuration conf, boolean close)
    throws IOException {
    return copyBytesAndGenerateCRC(in, out, conf.getInt("io.file.buffer.size", 4096),  close);
  }
  
  /** Reads len bytes in a loop.
   * @param in The InputStream to read from
   * @param buf The buffer to fill
   * @param off offset from the buffer
   * @param len the length of bytes to read
   * @throws IOException if it could not read requested number of bytes 
   * for any reason (including EOF)
   */
  public static void readFully( InputStream in, byte buf[],
      int off, int len ) throws IOException {
    int toRead = len;
    while ( toRead > 0 ) {
      int ret = in.read( buf, off, toRead );
      if ( ret < 0 ) {
        throw new IOException( "Premeture EOF from inputStream");
      }
      toRead -= ret;
      off += ret;
    }
  }
  
  /**
   * Reads len bytes in a loop using the channel of the stream
   */
  public static int readFileChannelFully(FileChannel fileChannel, byte buf[], 
      int off, int len, long offset, boolean throwOnEof) throws IOException {
    ByteBuffer byteBuffer = ByteBuffer.wrap(buf, off, len);
    int toRead = len;
    int dataRead = 0;
    while (toRead > 0) {
      int ret = fileChannel.read(byteBuffer, offset);
      if (ret < 0) {
        if (throwOnEof) {
          throw new IOException( "Premeture EOF from inputStream");
        } else {
          return dataRead;
        }
      }
      toRead -= ret;
      offset += ret;
      dataRead += ret;
    }
    return dataRead;
  }

  public static int readDRCFileChannelFully(FileChannel fileChannel, byte buf[],
      int off, int len, long offset, byte checksumbuf[], int checkoff, 
      int bytesPerChecksum, int checksumSize, boolean throwOnEof) throws IOException {
    
    int dataBufOff = off;
    int checksumBufOff = checkoff;
    long fileOff = offset;

    int haveread = 0;

    while(true) {
      ByteBuffer dataBytes = ByteBuffer.allocate(bytesPerChecksum);
      ByteBuffer checksumBytes = ByteBuffer.allocate(checksumSize);
      
      int datanum = fileChannel.read(dataBytes, fileOff);
      fileOff += datanum;
      int checksumnum = fileChannel.read(checksumBytes, fileOff);
      fileOff += checksumnum;
      
      if(datanum<0 | checksumnum<0 | len <=0) {
        break;
      }

      System.arraycopy(dataBytes.array(), 0, buf, dataBufOff, datanum);
      dataBufOff += datanum;
      System.arraycopy(checksumBytes.array(), 0, checksumbuf, checksumBufOff, checksumnum);
      checksumBufOff += checksumnum; 

      haveread += datanum;
      len -= datanum;
    }
    return haveread;
  }







  /** Reads len bytes in a loop using the channel of the stream
   * @param fileChannel a FileChannel to read len bytes into buf
   * @param byteBuffer The buffer to fill
   * @param off offset from the buffer
   * @param len the length of bytes to read
   * @param throwOnEof if EOF, throws exception or return less bytes
   * @return bytes actually read
   * @throws IOException if it could not read requested number of bytes 
   * for any reason (including EOF)
   */
  public static int readFileChannelFully( FileChannel fileChannel, ByteBuffer byteBuffer,
      int off, int len, boolean throwOnEof ) throws IOException {
    int toRead = len;
    int dataRead = 0;
    while ( toRead > 0 ) {
      int ret = fileChannel.read(byteBuffer);
      if ( ret < 0 ) {
        if (throwOnEof) {
          throw new IOException( "Premeture EOF from inputStream");
        } else {
          return dataRead;
        }
      }
      toRead -= ret;
      off += ret;
      dataRead += ret;
    }
    return dataRead;
  }
  
  /** Reads len bytes in a loop using the channel of the stream
   * @param fileChannel a FileChannel to read len bytes into buf
   * @param buf The buffer to fill
   * @param off offset from the buffer
   * @param len the length of bytes to read
   * @throws IOException if it could not read requested number of bytes 
   * for any reason (including EOF)
   */
  public static void readFileChannelFully( FileChannel fileChannel, byte buf[],
      int off, int len ) throws IOException {
    ByteBuffer byteBuffer = ByteBuffer.wrap(buf, off, len);
    readFileChannelFully(fileChannel, byteBuffer, off, len, true);
  }
  
  /** Similar to readFully(). Skips bytes in a loop.
   * @param in The InputStream to skip bytes from
   * @param len number of bytes to skip.
   * @throws IOException if it could not skip requested number of bytes 
   * for any reason (including EOF)
   */
  public static void skipFully( InputStream in, long len ) throws IOException {
    while ( len > 0 ) {
      long ret = in.skip( len );
      if ( ret < 0 ) {
        throw new IOException( "Premeture EOF from inputStream");
      }
      len -= ret;
    }
  }
  
  /**
   * Close the Closeable objects and <b>ignore</b> any {@link IOException} or 
   * null pointers. Must only be used for cleanup in exception handlers.
   * @param log the log to record problems to at debug level. Can be null.
   * @param closeables the objects to close
   */
  public static void cleanup(Log log, java.io.Closeable... closeables) {
    for(java.io.Closeable c : closeables) {
      if (c != null) {
        try {
          c.close();
        } catch(IOException e) {
          if (log != null && log.isDebugEnabled()) {
            log.debug("Exception in closing " + c, e);
          }
        }
      }
    }
  }

  /**
   * Closes the stream ignoring {@link IOException}.
   * Must only be called in cleaning up from exception handlers.
   * @param stream the Stream to close
   */
  public static void closeStream( java.io.Closeable stream ) {
    cleanup(null, stream);
  }
  
  /**
   * Closes the socket ignoring {@link IOException} 
   * @param sock the Socket to close
   */
  public static void closeSocket( Socket sock ) {
    // avoids try { close() } dance
    if ( sock != null ) {
      try {
       sock.close();
      } catch ( IOException ignored ) {
      }
    }
  }
  
  /** /dev/null of OutputStreams.
   */
  public static class NullOutputStream extends OutputStream {
    public void write(byte[] b, int off, int len) throws IOException {
    }

    public void write(int b) throws IOException {
    }
  } 

  /**
   * Write a ByteBuffer to a FileChannel at a given offset, 
   * handling short writes.
   * 
   * @param fc               The FileChannel to write to
   * @param buf              The input buffer
   * @param offset           The offset in the file to start writing at
   * @throws IOException     On I/O error
   */
  public static void writeFully(FileChannel fc, ByteBuffer buf,
      long offset) throws IOException {
    do {
      offset += fc.write(buf, offset);
    } while (buf.remaining() > 0);
  }
}
