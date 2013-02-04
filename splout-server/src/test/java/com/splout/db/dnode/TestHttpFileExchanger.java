package com.splout.db.dnode;

/*
 * #%L
 * Splout SQL Server
 * %%
 * Copyright (C) 2012 - 2013 Datasalt Systems S.L.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */

import static org.junit.Assert.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import com.google.common.io.Files;
import com.splout.db.common.SploutConfiguration;
import com.splout.db.common.TestUtils;
import com.splout.db.dnode.HttpFileExchanger.ReceiveFileCallback;

public class TestHttpFileExchanger {

	public final static String TMP_FILE = "tmp-file-" + TestHttpFileExchanger.class.getName();
	public final static String TMP_DOWNLOAD_DIR = "tmp-download-" + TestHttpFileExchanger.class.getName();

	public final static String FOO_CONTENT = "This is a text file. It is not very big, but it is ok for a test. "
		    + "Specially if we use a small buffer size to try to detect race conditions.";
	
	@Test
	public void testConcurrentTransfersSameFileNotAllowed() throws IOException, InterruptedException {
		final File fileToSend = new File(TMP_FILE);

		// big file to make it unlikely that several concurrent transfers will not colide.
		BufferedWriter writer = new BufferedWriter(new FileWriter(fileToSend));
		for(int i = 0; i < 5000; i++) {
			writer.write(FOO_CONTENT + "\n");
		}

		SploutConfiguration conf = SploutConfiguration.getTestConfig();
		conf.setProperty(FetcherProperties.DOWNLOAD_BUFFER, 16);
		conf.setProperty(FetcherProperties.TEMP_DIR, TMP_DOWNLOAD_DIR);

		final AtomicInteger failed = new AtomicInteger(0);

		HttpFileExchanger exchanger = new HttpFileExchanger(conf, new ReceiveFileCallback() {
			@Override
      public void onProgress(String tablespace, Integer partition, Long version, File file,
          long totalSize, long sizeDownloaded) {
      }
			@Override
      public void onFileReceived(String tablespace, Integer partition, Long version, File file) {
      }
			@Override
      public void onBadCRC(String tablespace, Integer partition, Long version, File file) {
      }
			@Override
      public void onError(Throwable t, String tablespace, Integer partition, Long version, File file) {
				if(t.getMessage().contains("Incoming file already being transferred")) {
					failed.incrementAndGet();
				}
			}
		});
		exchanger.init();
		exchanger.run();

		String dnodeHost = conf.getString(DNodeProperties.HOST);
		int httpPort = conf.getInt(HttpFileExchangerProperties.HTTP_PORT);

		exchanger.send("t1", 0, 1l, fileToSend, "http://" + dnodeHost + ":" + httpPort, true);
		exchanger.send("t1", 0, 1l, fileToSend, "http://" + dnodeHost + ":" + httpPort, true);
		
		final File downloadedFile = new File(new File(TMP_DOWNLOAD_DIR,
		    DNodeHandler.getLocalStoragePartitionRelativePath("t1", 0, 1l)), fileToSend.getName());

		new TestUtils.NotWaitingForeverCondition() {

			@Override
			public boolean endCondition() {
				return failed.get() > 0;
			}
		}.waitAtMost(5000);
		
		new TestUtils.NotWaitingForeverCondition() {
			@Override
			public boolean endCondition() {
				return downloadedFile.exists() && downloadedFile.length() == fileToSend.length();
			}
		}.waitAtMost(5000);
		
		exchanger.close();
		exchanger.join();

		fileToSend.delete();
		downloadedFile.delete();
		downloadedFile.getParentFile().delete();
		
		assertEquals(0, exchanger.getCurrentTransfers().size());
		assertEquals(1, failed.get());
	}
	
	@Test
	public void test() throws IOException, InterruptedException {
		final File fileToSend = new File(TMP_FILE);

		String fileContents = "This is a text file. It is not very big, but it is ok for a test. "
		    + "Specially if we use a small buffer size to try to detect race conditions.";
		Files.write(fileContents.getBytes(), fileToSend);

		SploutConfiguration conf = SploutConfiguration.getTestConfig();
		conf.setProperty(FetcherProperties.DOWNLOAD_BUFFER, 16);
		conf.setProperty(FetcherProperties.TEMP_DIR, TMP_DOWNLOAD_DIR);

		final AtomicBoolean receivedOk = new AtomicBoolean(false);

		HttpFileExchanger exchanger = new HttpFileExchanger(conf, new ReceiveFileCallback() {
			@Override
      public void onProgress(String tablespace, Integer partition, Long version, File file,
          long totalSize, long sizeDownloaded) {
      }
			@Override
      public void onFileReceived(String tablespace, Integer partition, Long version, File file) {
				receivedOk.set(true);
      }
			@Override
      public void onBadCRC(String tablespace, Integer partition, Long version, File file) {
      }
			@Override
      public void onError(Throwable t, String tablespace, Integer partition, Long version, File file) {
			}
		});
		exchanger.init();
		exchanger.run();

		String dnodeHost = conf.getString(DNodeProperties.HOST);
		int httpPort = conf.getInt(HttpFileExchangerProperties.HTTP_PORT);

		exchanger.send("t1", 0, 1l, fileToSend, "http://" + dnodeHost + ":" + httpPort, true);

		final File downloadedFile = new File(new File(TMP_DOWNLOAD_DIR,
		    DNodeHandler.getLocalStoragePartitionRelativePath("t1", 0, 1l)), fileToSend.getName());

		new TestUtils.NotWaitingForeverCondition() {

			@Override
			public boolean endCondition() {
				return downloadedFile.exists() && downloadedFile.length() == fileToSend.length();
			}
		}.waitAtMost(5000);

		Assert.assertEquals(Files.toString(fileToSend, Charset.defaultCharset()),
		    Files.toString(downloadedFile, Charset.defaultCharset()));

		Assert.assertTrue(receivedOk.get());

		exchanger.close();
		exchanger.join();

		fileToSend.delete();
		assertEquals(0, exchanger.getCurrentTransfers().size());
		downloadedFile.delete();
		downloadedFile.getParentFile().delete();
	}
}
