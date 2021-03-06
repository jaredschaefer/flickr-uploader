package com.rafali.flickruploader;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import javax.jdo.annotations.IdentityType;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;
import javax.jdo.annotations.PrimaryKey;

@PersistenceCapable(identityType = IdentityType.APPLICATION)
public class AppInstall implements Serializable {
	private static final long serialVersionUID = 1L;

	public enum FIELD {
		deviceId, androidDevice, emails, ownerIds, dateCreation
	}

	@PrimaryKey
	@Persistent
	private String deviceId;

	@Persistent(serialized = "true", defaultFetchGroup = "true")
	private AndroidDevice androidDevice;

	@Persistent
	private List<String> emails;

	@Persistent
	private List<String> ownerIds;

	@Persistent
	private Date dateCreation;

	public AppInstall(String deviceId, AndroidDevice androidDevice, Collection<String> emails) {
		this.deviceId = deviceId;
		this.androidDevice = androidDevice;
		this.emails = emails == null ? new ArrayList<String>() : new ArrayList<String>(new HashSet<String>(emails));
		this.dateCreation = new Date();
	}

	public void setAndroidDevice(AndroidDevice androidDevice) {
		this.androidDevice = androidDevice;
	}

	public AndroidDevice getAndroidDevice() {
		return androidDevice;
	}

	public List<String> getEmails() {
		return emails;
	}

	public String getDeviceId() {
		return deviceId;
	}

	public List<String> getOwnerIds() {
		return Collections.unmodifiableList(ownerIds == null ? new ArrayList<String>() : ownerIds);
	}

	@Override
	public String toString() {
		return androidDevice + "-" + dateCreation + " : " + ownerIds + " : " + emails;
	}

	public void setDateCreation(Date dateCreation) {
		this.dateCreation = dateCreation;
	}

	public Date getDateCreation() {
		return this.dateCreation;
	}

	public void addOwnerId(String userId) {
		if (ownerIds == null)
			ownerIds = new ArrayList<String>();
		if (!ownerIds.contains(userId))
			ownerIds.add(userId);
	}

}