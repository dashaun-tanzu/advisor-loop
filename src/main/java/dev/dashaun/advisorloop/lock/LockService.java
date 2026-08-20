package dev.dashaun.advisorloop.lock;

import dev.dashaun.advisorloop.config.AdvisorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Single-instance guard so two passes cannot fight over the same clones and mapping
 * store.
 */
@Component
public class LockService {

	private static final Logger log = LoggerFactory.getLogger(LockService.class);

	private final AdvisorProperties props;

	private RandomAccessFile raf;

	private FileChannel channel;

	private FileLock lock;

	public LockService(AdvisorProperties props) {
		this.props = props;
	}

	public synchronized boolean tryAcquire() {
		if (lock != null)
			return true;
		try {
			Files.createDirectories(props.workspace());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot create workspace " + props.workspace(), e);
		}
		Path lockPath = lockPath();
		try {
			raf = new RandomAccessFile(lockPath.toFile(), "rw");
			channel = raf.getChannel();
			lock = channel.tryLock();
			if (lock == null) {
				release();
				return false;
			}
			raf.setLength(0);
			raf.write(describeHolder().getBytes(StandardCharsets.UTF_8));
			return true;
		}
		catch (IOException e) {
			release();
			log.warn("Could not acquire lock at {}: {}", lockPath, e.getMessage());
			return false;
		}
	}

	public String currentHolder() {
		Path p = lockPath();
		if (!Files.isRegularFile(p))
			return "(unknown)";
		try {
			return Files.readString(p, StandardCharsets.UTF_8).strip();
		}
		catch (IOException e) {
			return "(unknown)";
		}
	}

	public synchronized void release() {
		try {
			if (lock != null && lock.isValid())
				lock.release();
		}
		catch (IOException ignored) {
			/* closing anyway */ }
		try {
			if (channel != null)
				channel.close();
		}
		catch (IOException ignored) {
			/* closing anyway */ }
		try {
			if (raf != null)
				raf.close();
		}
		catch (IOException ignored) {
			/* closing anyway */ }
		lock = null;
		channel = null;
		raf = null;
	}

	private Path lockPath() {
		return props.workspace().resolve(".lock");
	}

	private static String describeHolder() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (Exception e) {
			host = "unknown";
		}
		return "pid=" + ManagementFactory.getRuntimeMXBean().getName() + " host=" + host + " at=" + Instant.now()
				+ "\n";
	}

}
