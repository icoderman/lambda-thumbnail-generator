package com.watkins.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import net.coobird.thumbnailator.Thumbnails;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class S3EventProcessorCreateThumbnail implements RequestHandler<S3Event, String> {

	private static final int MAX_WIDTH = 120;
	private static final int MAX_HEIGHT = 240;

	private final String JPG_TYPE = (String) "jpg";
	private final String JPG_MIME = (String) "image/jpeg";
	private final String PNG_TYPE = (String) "png";
	private final String PNG_MIME = (String) "image/png";

	public String handleRequest(S3Event s3event, Context context) {
		try {
			S3EventNotificationRecord record = s3event.getRecords().get(0);

			String srcBucket = record.getS3().getBucket().getName();
			// Object key may have spaces or unicode non-ASCII characters.
			String srcKey = record.getS3().getObject().getKey().replace('+', ' ');
			srcKey = URLDecoder.decode(srcKey, "UTF-8");

			//String dstBucket = srcBucket + "resized";
			//String dstKey = "resized-" + srcKey;
			String dstKey =  srcKey.replace("original/","thumbnail/");

			// Sanity check: validate that source and destination are different
			// buckets.
			/*
			if (srcBucket.equals(dstBucket)) {
				System.out.println("Destination bucket must not match source bucket.");
				return "";
			}
			*/

			// Infer the image type.
			Matcher matcher = Pattern.compile(".*\\.([^\\.]*)").matcher(srcKey);
			if (!matcher.matches()) {
				System.out.println("Unable to infer image type for key " + srcKey);
				return "";
			}
			String imageType = matcher.group(1);
			if (!(JPG_TYPE.equals(imageType)) && !(PNG_TYPE.equals(imageType))) {
				System.out.println("Skipping non-image " + srcKey);
				return "";
			}

			// Download the image from S3 into a stream
			AmazonS3 s3Client = new AmazonS3Client();
			S3Object s3Object = s3Client.getObject(new GetObjectRequest(srcBucket, srcKey));
			InputStream objectData = s3Object.getObjectContent();

			ByteArrayOutputStream os = new ByteArrayOutputStream();
			Thumbnails.of(objectData).size(MAX_WIDTH, MAX_HEIGHT).toOutputStream(os);
/*
			// Read the source image
			BufferedImage srcImage = ImageIO.read(objectData);
			int srcHeight = srcImage.getHeight();
			int srcWidth = srcImage.getWidth();
			// Infer the scaling factor to avoid stretching the image
			// unnaturally
			float scalingFactor = Math.min(MAX_WIDTH / srcWidth, MAX_HEIGHT / srcHeight);
			int width = (int) (scalingFactor * srcWidth);
			int height = (int) (scalingFactor * srcHeight);

			//BufferedImage resizedImage = getBufferedImage(srcImage, width, height);
			BufferedImage resizedImage = getScaledInstance(srcImage, width, height, RenderingHints.VALUE_INTERPOLATION_BILINEAR, true);

			// Re-encode image to target format
			ByteArrayOutputStream os = new ByteArrayOutputStream();
			ImageIO.write(resizedImage, imageType, os);
			InputStream is = new ByteArrayInputStream(os.toByteArray());
			// Set Content-Length and Content-Type
*/
			InputStream is = new ByteArrayInputStream(os.toByteArray());
			ObjectMetadata meta = new ObjectMetadata();
			meta.setContentLength(os.size());
			if (JPG_TYPE.equals(imageType)) {
				meta.setContentType(JPG_MIME);
			}
			if (PNG_TYPE.equals(imageType)) {
				meta.setContentType(PNG_MIME);
			}

			// Uploading to S3 destination bucket
			System.out.println("Writing to: " + srcBucket + "/" + dstKey);
			s3Client.putObject(srcBucket, dstKey, is, meta);
			System.out.println("Successfully resized " + srcBucket + "/" + srcKey + " and uploaded to " + srcBucket + "/" + dstKey);
			return "Ok";
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private BufferedImage getBufferedImage(BufferedImage srcImage, int width, int height) {
		BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = resizedImage.createGraphics();
		// Fill with white before applying semi-transparent (alpha) images
		g.setPaint(Color.white);
		g.fillRect(0, 0, width, height);
		// Simple bilinear resize
		// If you want higher quality algorithms, check this link:
		// https://today.java.net/pub/a/today/2007/04/03/perils-of-image-getscaledinstance.html
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(srcImage, 0, 0, width, height, null);
		g.dispose();
		return resizedImage;
	}

	/**
	 * Convenience method that returns a scaled instance of the
	 * provided {@code BufferedImage}.
	 *
	 * @param img the original image to be scaled
	 * @param targetWidth the desired width of the scaled instance,
	 *    in pixels
	 * @param targetHeight the desired height of the scaled instance,
	 *    in pixels
	 * @param hint one of the rendering hints that corresponds to
	 *    {@code RenderingHints.KEY_INTERPOLATION} (e.g.
	 *    {@code RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR},
	 *    {@code RenderingHints.VALUE_INTERPOLATION_BILINEAR},
	 *    {@code RenderingHints.VALUE_INTERPOLATION_BICUBIC})
	 * @param higherQuality if true, this method will use a multi-step
	 *    scaling technique that provides higher quality than the usual
	 *    one-step technique (only useful in downscaling cases, where
	 *    {@code targetWidth} or {@code targetHeight} is
	 *    smaller than the original dimensions, and generally only when
	 *    the {@code BILINEAR} hint is specified)
	 * @return a scaled version of the original {@code BufferedImage}
	 */
	public BufferedImage getScaledInstance(BufferedImage img,
	                                       int targetWidth,
	                                       int targetHeight,
	                                       Object hint,
	                                       boolean higherQuality)
	{
		int type = (img.getTransparency() == Transparency.OPAQUE) ?
				BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
		BufferedImage ret = (BufferedImage)img;
		int w, h;
		if (higherQuality) {
			// Use multi-step technique: start with original size, then
			// scale down in multiple passes with drawImage()
			// until the target size is reached
			w = img.getWidth();
			h = img.getHeight();
		} else {
			// Use one-step technique: scale directly from original
			// size to target size with a single drawImage() call
			w = targetWidth;
			h = targetHeight;
		}

		do {
			if (higherQuality && w > targetWidth) {
				w /= 2;
				if (w < targetWidth) {
					w = targetWidth;
				}
			}

			if (higherQuality && h > targetHeight) {
				h /= 2;
				if (h < targetHeight) {
					h = targetHeight;
				}
			}

			BufferedImage tmp = new BufferedImage(w, h, type);
			Graphics2D g2 = tmp.createGraphics();
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
			g2.drawImage(ret, 0, 0, w, h, null);
			g2.dispose();

			ret = tmp;
		} while (w != targetWidth || h != targetHeight);

		return ret;
	}
}
