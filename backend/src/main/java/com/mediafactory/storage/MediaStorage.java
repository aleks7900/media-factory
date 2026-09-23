package com.mediafactory.storage;
public interface MediaStorage {
 void putOriginal(String key, byte[] bytes, String contentType);
 byte[] read(String key);
 void checkAvailable();
}
