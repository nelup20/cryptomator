package org.cryptomator.ui.model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.cryptomator.cryptolib.Cryptors;
import org.cryptomator.cryptolib.api.Cryptor;
import org.cryptomator.cryptolib.api.FileHeader;
import org.cryptomator.ui.settings.Localization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains the collective knowledge of all creatures who were alive during the development of vault format 3.
 * This class uses no external classes from the crypto or shortening layer by purpose, so we don't need legacy code inside these.
 */
@Singleton
class UpgradeVersion4to5 extends UpgradeStrategy {

	private static final Logger LOG = LoggerFactory.getLogger(UpgradeVersion4to5.class);
	private static final Pattern BASE32_PATTERN = Pattern.compile("^([A-Z2-7]{8})*[A-Z2-7=]{8}");

	@Inject
	public UpgradeVersion4to5(SecureRandom secureRandom, Localization localization) {
		super(Cryptors.version1(secureRandom), localization, 4, 5);
	}

	@Override
	public String getNotification(Vault vault) {
		return localization.getString("upgrade.version3to4.msg");
	}

	@Override
	protected void upgrade(Vault vault, Cryptor cryptor) throws UpgradeFailedException {
		Path dataDir = vault.path().get().resolve("d");
		if (!Files.isDirectory(dataDir)) {
			return; // empty vault. no migration needed.
		}
		try {
			Files.walkFileTree(dataDir, new SimpleFileVisitor<Path>() {

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (BASE32_PATTERN.matcher(file.getFileName().toString()).find() && attrs.size() > cryptor.fileHeaderCryptor().headerSize()) {
						migrate(file, attrs, cryptor);
					}
					return FileVisitResult.CONTINUE;
				}

			});
		} catch (IOException e) {
			LOG.error("Migration failed.", e);
			throw new UpgradeFailedException(localization.getString("upgrade.version3to4.err.io"));
		}
		LOG.info("Migration finished.");
	}

	private void migrate(Path file, BasicFileAttributes attrs, Cryptor cryptor) throws IOException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			// read header:
			ByteBuffer headerBuf = ByteBuffer.allocate(cryptor.fileHeaderCryptor().headerSize());
			ch.read(headerBuf);
			headerBuf.flip();
			FileHeader header = cryptor.fileHeaderCryptor().decryptHeader(headerBuf);
			long cleartextSize = header.getFilesize();
			if (cleartextSize < 0) {
				LOG.info("Skipping already migrated file {}.", file);
				return;
			} else if (cleartextSize > attrs.size()) {
				LOG.warn("Skipping file {} with invalid file size {}/{}", file, cleartextSize, attrs.size());
				return;
			}
			int headerSize = cryptor.fileHeaderCryptor().headerSize();
			int ciphertextChunkSize = cryptor.fileContentCryptor().ciphertextChunkSize();
			int cleartextChunkSize = cryptor.fileContentCryptor().cleartextChunkSize();
			long newCiphertextSize = Cryptors.ciphertextSize(cleartextSize, cryptor);
			long newEOF = headerSize + newCiphertextSize;
			long newFullChunks = newCiphertextSize / ciphertextChunkSize; // int-truncation
			long newAdditionalCiphertextBytes = newCiphertextSize % ciphertextChunkSize;
			if (newAdditionalCiphertextBytes == 0) {
				// (new) last block is already correct. just truncate:
				LOG.info("Migrating {} of cleartext size {}: Truncating to new ciphertext size: {}", file, cleartextSize, newEOF);
				ch.truncate(newEOF);
			} else {
				// last block may contain padding and needs to be re-encrypted:
				long lastChunkIdx = newFullChunks;
				LOG.info("Migrating {} of cleartext size {}: Re-encrypting chunk {}. New ciphertext size: {}", file, cleartextSize, lastChunkIdx, newEOF);
				long beginOfLastChunk = headerSize + lastChunkIdx * ciphertextChunkSize;
				assert beginOfLastChunk < newEOF;
				int lastCleartextChunkLength = (int) (cleartextSize % cleartextChunkSize);
				assert lastCleartextChunkLength < cleartextChunkSize;
				assert lastCleartextChunkLength > 0;
				ch.position(beginOfLastChunk);
				ByteBuffer lastCiphertextChunk = ByteBuffer.allocate(ciphertextChunkSize);
				int read = ch.read(lastCiphertextChunk);
				if (read != -1) {
					lastCiphertextChunk.flip();
					ByteBuffer lastCleartextChunk = cryptor.fileContentCryptor().decryptChunk(lastCiphertextChunk, lastChunkIdx, header, true);
					lastCleartextChunk.position(0).limit(lastCleartextChunkLength);
					assert lastCleartextChunk.remaining() == lastCleartextChunkLength;
					ByteBuffer newLastChunkCiphertext = cryptor.fileContentCryptor().encryptChunk(lastCleartextChunk, lastChunkIdx, header);
					ch.truncate(beginOfLastChunk);
					ch.write(newLastChunkCiphertext);
				} else {
					LOG.error("Reached EOF at position {}/{}", beginOfLastChunk, newEOF);
					return; // must exit method before changing header!
				}
			}
			header.setFilesize(-1l);
			ByteBuffer newHeaderBuf = cryptor.fileHeaderCryptor().encryptHeader(header);
			ch.position(0);
			ch.write(newHeaderBuf);
		}
	}

}