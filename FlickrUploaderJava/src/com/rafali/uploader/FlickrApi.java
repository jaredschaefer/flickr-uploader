package com.rafali.uploader;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Sets;
import com.googlecode.androidannotations.api.BackgroundExecutor;
import com.googlecode.flickrjandroid.Flickr;
import com.googlecode.flickrjandroid.FlickrException;
import com.googlecode.flickrjandroid.RequestContext;
import com.googlecode.flickrjandroid.oauth.OAuth;
import com.googlecode.flickrjandroid.oauth.OAuthToken;
import com.googlecode.flickrjandroid.photos.Permissions;
import com.googlecode.flickrjandroid.photos.Photo;
import com.googlecode.flickrjandroid.photos.PhotoList;
import com.googlecode.flickrjandroid.photos.SearchParameters;
import com.googlecode.flickrjandroid.photosets.Photoset;
import com.googlecode.flickrjandroid.photosets.Photosets;
import com.googlecode.flickrjandroid.uploader.UploadMetaData;
import com.rafali.flickruploader.FlickrApi.PRIVACY;
import com.rafali.flickruploader.Folder;
import com.rafali.flickruploader.Image;
import com.rafali.flickruploader.ProgressListener;
import com.rafali.flickruploader.STR;

public class FlickrApi {

	private static final HashSet<String> EXTRAS_MACHINE_TAGS = Sets.newHashSet("machine_tags");

	static final String TAG = FlickrApi.class.getSimpleName();

	private static final String API_SECRET = Utils.getString("flickr_api_secret");
	private static final String API_KEY = Utils.getString("flickr_api_key");
	private static Flickr flickr = new Flickr(API_KEY, API_SECRET);

	private static OAuth auth;

	public static Flickr get() {
		if (auth == null) {
			updateOauth();
		}
		RequestContext.getRequestContext().setOAuth(auth);
		return flickr;
	}

	public static void reset() {
		auth = null;
		uploadedPhotos.clear();
		updateOauth();
	}

	private static void updateOauth() {
		String accessToken = Utils.getStringProperty(STR.accessToken);
		String accessTokenSecret = Utils.getStringProperty(STR.accessTokenSecret);
		authentified = ToolString.isNotBlank(accessToken) && ToolString.isNotBlank(accessTokenSecret);
		// String userId = getStringProperty("userId");
		auth = new OAuth();
		auth.setToken(new OAuthToken(accessToken, accessTokenSecret));
	}

	private static boolean authentified = false;

	private static Map<String, String> uploadedPhotos = Utils.getMapProperty(STR.uploadedPhotos);
	private static Map<String, String> uploadedFolders = Utils.getMapProperty(STR.uploadedFolders);
	private static Map<String, PRIVACY> photosPrivacy = Utils.getMapProperty(STR.photosPrivacy, PRIVACY.class);

	static {
		BackgroundExecutor.execute(new Runnable() {
			@Override
			public void run() {
				// syncUploadedPhotosMap();
			}
		});
	}

	static PRIVACY getPrivacy(Photo photo) {
		if (photo.isPublicFlag()) {
			return PRIVACY.PUBLIC;
		} else if (photo.isFamilyFlag() && photo.isFriendFlag()) {
			return PRIVACY.FRIENDS_FAMILY;
		} else if (photo.isFamilyFlag()) {
			return PRIVACY.FAMILY;
		} else if (photo.isFriendFlag()) {
			return PRIVACY.FRIENDS;
		}
		return PRIVACY.PRIVATE;
	}

	static long lastSync = 0;
	public static void syncUploadedPhotosMap(final boolean force) {
		if (System.currentTimeMillis() - lastSync > 5000) {
			lastSync = System.currentTimeMillis();
			BackgroundExecutor.execute(new Runnable() {
				@Override
				public void run() {
					try {
						synchronized (API_KEY) {
							if (force || FlickrApi.uploadedPhotos.isEmpty()) {
								Map<String, String> uploadedPhotos;
								if (FlickrApi.uploadedPhotos.isEmpty()) {
									uploadedPhotos = FlickrApi.uploadedPhotos;
								} else {
									uploadedPhotos = new LinkedHashMap<String, String>();
								}
								int totalPage = 10;
								int page = 1;
								int per_page = 500;
								int count = 0;
								// fetching all uploaded photos
								// the flickr query is not consistent (not all photos are retrieved)
								while (page <= totalPage) {
									lastSync = System.currentTimeMillis();
									SearchParameters params = new SearchParameters();
									params.setUserId(Utils.getStringProperty(STR.userId));
									params.setMachineTags(new String[] { "file:sha1sig=" });
									params.setExtras(EXTRAS_MACHINE_TAGS);
									PhotoList photoList = FlickrApi.get().getPhotosInterface().search(params, per_page, page);
									totalPage = photoList.getPages();
									per_page = photoList.getPerPage();
									count += photoList.size();
									if (!photoList.isEmpty()) {
										Log.d(TAG, count + " photos with machine tag fetched. page:" + page + "/" + totalPage);
										for (Photo photo : photoList) {
											for (String tag : photo.getMachineTags()) {
												if (tag.startsWith("file:sha1sig")) {
													uploadedPhotos.put(tag, photo.getId());
													photosPrivacy.put(photo.getId(), getPrivacy(photo));
													// System.out.println(tag + " = " + photo.getId());
												}
											}
										}
										page++;
									} else {
										break;
									}
								}
								if (FlickrApi.uploadedPhotos != uploadedPhotos) {
									// as all photos may not have been retrieve, we need to check which one have really been deleted manually
									FlickrApi.uploadedPhotos.keySet().removeAll(uploadedPhotos.keySet());
									for (String tag : FlickrApi.uploadedPhotos.keySet()) {
										String photoId = FlickrApi.uploadedPhotos.get(tag);
										try {
											Photo photo = FlickrApi.get().getPhotosInterface().getPhoto(photoId);
											if (photo != null) {
												Log.d(TAG, photoId + "=" + tag + " still exist");
												uploadedPhotos.put(tag, photoId);
											}
										} catch (FlickrException e) {
											if ("1".equals(e.getErrorCode())) {// Photo not found
												Log.d(TAG, photoId + "=" + tag + " still no longer exist");
											} else {
												Log.e(TAG, e.getMessage(), e);
											}
										} catch (Throwable e) {
											Log.e(TAG, e.getMessage(), e);
										}
									}
									FlickrApi.uploadedPhotos = uploadedPhotos;
								}
								Utils.setMapProperty(STR.uploadedPhotos, FlickrApi.uploadedPhotos);
								Utils.setEnumMapProperty(STR.photosPrivacy, photosPrivacy);
							}
						}
					} catch (Throwable e) {
						Log.e(TAG, e.getMessage(), e);
					}
				}
			});
		}
	}

	public static void ensureOrdered(String photosetId) {
		try {
			if (photosetId != null) {
				List<String> instantPhotoIds = new ArrayList<String>();
				int page = 1;
				int per_page = 500;
				int totalPage = 10;
				int count = 0;
				while (page <= totalPage) {
					lastSync = System.currentTimeMillis();
					PhotoList photos = FlickrApi.get().getPhotosetsInterface().getPhotos(photosetId, EXTRAS_MACHINE_TAGS, Flickr.PRIVACY_LEVEL_NO_FILTER, per_page, page);
					Log.d(TAG, "nb photos uploaded : " + photos.size());
					if (photos.isEmpty()) {
						break;
					} else {
						count += photos.size();
						per_page = photos.getPerPage();
						totalPage = photos.getPages();
						Log.d(TAG, count + " photos fetched for " + photosetId + ", page:" + page + "/" + totalPage);
						page++;
						for (Photo photo : photos) {
							instantPhotoIds.add(photo.getId());
							// as long as we are fetching data, let's make sure we have (again) the machine_tags
							for (String tag : photo.getMachineTags()) {
								if (tag.startsWith("file:sha1sig")) {
									uploadedPhotos.put(tag, photo.getId());
								}
							}
						}
					}
				}
				Utils.setMapProperty(STR.uploadedPhotos, uploadedPhotos);

				List<Image> loadImages = Utils.loadImages(null);
				Log.d(TAG, "loadImages : " + loadImages.size());
				List<String> photoIds = new ArrayList<String>();
				for (Image image : loadImages) {
					String sha1tag = Utils.getSHA1tag(image);
					String photoId = uploadedPhotos.get(sha1tag);
					if (photoId != null && instantPhotoIds.contains(photoId)) {
						photoIds.add(photoId);
					}
				}
				// Ordering.explicit(valuesInOrder);
				Log.i(TAG, "nb photos uploaded in " + photosetId + " : " + photoIds.size() + "/" + loadImages.size());
				if (!photoIds.isEmpty()) {
					boolean isOrdered = true;
					for (int i = 0; i < Math.min(photoIds.size(), instantPhotoIds.size()); i++) {
						if (photoIds.get(i).equals(instantPhotoIds.get(i))) {
							isOrdered = false;
							break;
						}
					}
					if (!isOrdered) {
						Log.d(TAG, "reordering photoset : \n" + photosetId + "\n => \n" + photoIds);
						FlickrApi.get().getPhotosetsInterface().reorderPhotos(photosetId, photoIds);
						FlickrApi.get().getPhotosetsInterface().setPrimaryPhoto(photosetId, photoIds.get(0));
					}
				}
			}
		} catch (Throwable e) {
			Log.e(TAG, e.getMessage(), e);
		}
	}

	public static void ensureOrdered(Folder folder) {
		ensureOrdered(uploadedFolders.get(folder.path));
	}

	public static boolean isUploaded(Object object) {
		if (object instanceof Image) {
			String sha1tag = Utils.getSHA1tag((Image) object);
			return uploadedPhotos.get(sha1tag) != null;
		} else if (object instanceof Folder) {
			for (Image image : ((Folder) object).images) {
				if (!isUploaded(image)) {
					return false;
				}
			}
		}
		return true;
	}

	public static String getPhotoId(Image image) {
		String sha1tag = Utils.getSHA1tag(image);
		return uploadedPhotos.get(sha1tag);
	}

	public static boolean containsUploadedPhotos(String photosetId) {
		try {
			int per_page = 100;
			// Log.i(TAG, "persisted uploadedPhotos : " + uploadedPhotos);
			lastSync = System.currentTimeMillis();
			PhotoList photos = FlickrApi.get().getPhotosetsInterface().getPhotos(photosetId, EXTRAS_MACHINE_TAGS, Flickr.PRIVACY_LEVEL_NO_FILTER, per_page, 1);
			Log.d(TAG, "nb photos uploaded : " + photos.size());
			for (Photo photo : photos) {
				for (String tag : photo.getMachineTags()) {
					if (tag.startsWith("file:sha1sig")) {
						return true;
					}
				}
			}
		} catch (Throwable e) {
			Log.e(TAG, e.getMessage(), e);
		}
		return false;
	}
	
	public static boolean upload(Image image, Folder folder, ProgressListener progressListener) {
		boolean success = false;
		int retry = 0;
		String photoId = null;
		String sha1tag = Utils.getSHA1tag(image);
		while (retry < 3 && !success) {
			String photosetId;
			if (folder != null) {
				photosetId = uploadedFolders.get(folder.path);
			} else {
				photosetId = Utils.getInstantAlbumId();
			}
			try {
				if (photosetId == null) {
					Photosets list = FlickrApi.get().getPhotosetsInterface().getList(Utils.getStringProperty(STR.userId));
					for (Photoset photoset : list.getPhotosets()) {
						if (folder != null) {
							if (folder.name.equals(photoset.getTitle()) && containsUploadedPhotos(photoset.getId())) {
								photosetId = photoset.getId();
								Log.d(TAG, folder.name + " : " + photosetId);
							}
						} else if (STR.instantUpload.equals(photoset.getTitle()) && containsUploadedPhotos(photoset.getId())) {
							photosetId = photoset.getId();
							Log.d(TAG, "instantAlbumId : " + photosetId);
							Utils.setStringProperty(STR.instantAlbumId, photosetId);
							break;
						}
					}
				}
				if (photoId == null) {
					String uri = image.path;
					String md5tag = "file:md5sum=" + Utils.getMD5Checksum(image);
					SearchParameters params = new SearchParameters();
					params.setUserId(Utils.getStringProperty(STR.userId));
					params.setMachineTags(new String[] { md5tag });
					PhotoList photoList = FlickrApi.get().getPhotosInterface().search(params, 1, 1);
					if (!photoList.isEmpty()) {
						Log.w(TAG, "already uploaded : " + photoList.get(0).getId() + " = " + md5tag + " = " + uri);
						photoId = photoList.get(0).getId();
						uploadedPhotos.put(sha1tag, photoId);
					} else {
						Log.d(TAG, "uploading : " + uri);
						File file = new File(uri);
						// InputStream inputStream = new FileInputStream(uri);
						UploadMetaData metaData = new UploadMetaData();
						PRIVACY privacy = Utils.getDefaultPrivacy();
						metaData.setFriendFlag(privacy == PRIVACY.FRIENDS || privacy == PRIVACY.FRIENDS_FAMILY);
						metaData.setFamilyFlag(privacy == PRIVACY.FAMILY || privacy == PRIVACY.FRIENDS_FAMILY);
						metaData.setPublicFlag(privacy == PRIVACY.PUBLIC);
						metaData.setTags(Arrays.asList(md5tag, sha1tag));
						long start = System.currentTimeMillis();
						photoId = FlickrApi.get().getUploader().upload(image.name, file, metaData, progressListener);
						Log.d(TAG, "photo uploaded in " + (System.currentTimeMillis() - start) + "ms : " + photoId);
						uploadedPhotos.put(sha1tag, photoId);
						photosPrivacy.put(photoId, privacy);
						Utils.setEnumMapProperty(STR.photosPrivacy, photosPrivacy);
					}
				}
				if (photoId != null) {
					if (ToolString.isBlank(photosetId)) {
						String title;
						if (folder != null) {
							title = folder.name;
						} else {
							title = STR.instantUpload;
						}
						String description = "Auto uploaded";
						Photoset photoset = FlickrApi.get().getPhotosetsInterface().create(title, description, photoId);
						photosetId = photoset.getId();

						if (folder != null) {
							uploadedFolders.put(folder.path, photosetId);
							Utils.setMapProperty(STR.uploadedFolders, uploadedFolders);
						} else {
							Utils.setStringProperty(STR.instantAlbumId, photosetId);
						}
					} else {
						FlickrApi.get().getPhotosetsInterface().addPhoto(photosetId, photoId);
					}
				}
				success = true;
			} catch (Throwable e) {
				if (e instanceof FlickrException) {
					FlickrException fe = (FlickrException) e;
					if ("1".equals(fe.getErrorCode())) {// Photoset not found
						if (folder != null) {
							uploadedFolders.remove(folder.path);
						} else if (photosetId != null) {
							if (photosetId.equals(Utils.getStringProperty(STR.instantAlbumId))) {
								Utils.clearProperty(STR.instantAlbumId);
							} else if (photosetId.equals(Utils.getStringProperty(STR.instantCustomAlbumId))) {
								Utils.clearProperty(STR.instantCustomAlbumId);
								Utils.clearProperty(STR.instantCustomAlbumTitle);
							}
						}
					} else if ("3".equals(fe.getErrorCode())) {// Photo already in set
						success = true;
					} else if ("98".equals(fe.getErrorCode())) {
						auth = null;
						authentified = false;
						break;
					}
				}
				if (!success) {
					Log.e(TAG, "retry " + retry + " : " + e.getMessage(), e);
					try {
						Thread.sleep((long) (Math.pow(2, retry) * 1000));
					} catch (InterruptedException ignore) {
					}
					retry++;
				}
			}
		}
		if (success) {
			uploadedPhotos.put(sha1tag, photoId);
			Utils.setMapProperty(STR.uploadedPhotos, uploadedPhotos);
		}
		return success;
	}

	public static boolean isAuthentified() {
		if (auth == null) {
			updateOauth();
		}
		return authentified;
	}

	protected static String alphabetString = "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
	protected static char[] alphabet = alphabetString.toCharArray();
	protected static int base_count = alphabet.length;
	public static final String FLICKR_SHORT_URL_PREFIX = "http://flic.kr/p/";

	public static String getShortUrl(String id) {
		String suffix = encode(Long.parseLong(id));
		return FLICKR_SHORT_URL_PREFIX + suffix;
	}

	public static String encode(long num) {
		String result = "";
		long div;
		int mod = 0;

		while (num >= base_count) {
			div = num / base_count;
			mod = (int) (num - (base_count * (long) div));
			result = alphabet[mod] + result;
			num = (long) div;
		}
		if (num > 0) {
			result = alphabet[(int) num] + result;
		}
		return result;
	}

	public static PRIVACY getPrivacy(Image image) {
		return photosPrivacy.get(getPhotoId(image));
	}

	public static void setPrivacy(final PRIVACY privacy, final List<String> photoIds) {
		BackgroundExecutor.execute(new Runnable() {
			@Override
			public void run() {
				try {
					for (String photoId : photoIds) {
						Permissions permissions = new Permissions();
						permissions.setPublicFlag(privacy == PRIVACY.PUBLIC);
						permissions.setFamilyFlag(privacy == PRIVACY.FAMILY || privacy == PRIVACY.FRIENDS_FAMILY);
						permissions.setFriendFlag(privacy == PRIVACY.FRIENDS || privacy == PRIVACY.FRIENDS_FAMILY);
						FlickrApi.get().getPhotosInterface().setPerms(photoId, permissions);
						photosPrivacy.put(photoId, privacy);
						Utils.setEnumMapProperty(STR.photosPrivacy, photosPrivacy);
						Log.d(TAG, "set privacy " + privacy + " on " + photoId);
					}
				} catch (Throwable e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}
		});
	}

	public static PRIVACY getPrivacy(String photoId) {
		return photosPrivacy.get(photoId);
	}

	public static Map<String, String> getPhotoSets() {
		Map<String, String> photoSets = new LinkedHashMap<String, String>();
		try {
			// Log.i(TAG, "persisted uploadedPhotos : " + uploadedPhotos);
			Photosets list = FlickrApi.get().getPhotosetsInterface().getList(Utils.getStringProperty(STR.userId));
			for (Photoset photoset : list.getPhotosets()) {
				photoSets.put(photoset.getId(), photoset.getTitle());
			}
		} catch (Throwable e) {
			Log.e(TAG, e.getMessage(), e);
		}
		return photoSets;
	}

}
