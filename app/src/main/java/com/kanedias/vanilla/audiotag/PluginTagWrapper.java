/*
 * Copyright (C) 2016-2018 Oleg Chernovskiy <adonai@xaker.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.kanedias.vanilla.audiotag;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import com.kanedias.vanilla.plugins.PluginConstants;
import com.kanedias.vanilla.plugins.PluginUtils;
import com.kanedias.vanilla.plugins.saf.SafPermissionHandler;
import com.kanedias.vanilla.plugins.saf.SafUtils;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.audio.generic.Utils;
import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import org.jaudiotagger.tag.TagOptionSingleton;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.valuepair.ImageFormats;
import org.jaudiotagger.tag.images.AndroidArtwork;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.reference.ID3V2Version;
import org.jaudiotagger.tag.reference.PictureTypes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.kanedias.vanilla.plugins.PluginConstants.ACTION_LAUNCH_PLUGIN;
import static com.kanedias.vanilla.plugins.PluginConstants.EXTRA_PARAM_P2P;
import static com.kanedias.vanilla.plugins.PluginConstants.EXTRA_PARAM_P2P_KEY;
import static com.kanedias.vanilla.plugins.PluginConstants.EXTRA_PARAM_P2P_VAL;
import static com.kanedias.vanilla.plugins.PluginConstants.EXTRA_PARAM_PLUGIN_APP;
import static com.kanedias.vanilla.plugins.PluginConstants.EXTRA_PARAM_URI;
import static com.kanedias.vanilla.plugins.PluginConstants.LOG_TAG;
import static com.kanedias.vanilla.plugins.PluginConstants.P2P_READ_ART;
import static com.kanedias.vanilla.plugins.PluginConstants.P2P_READ_TAG;
import static com.kanedias.vanilla.plugins.PluginConstants.P2P_WRITE_ART;
import static com.kanedias.vanilla.plugins.PluginConstants.P2P_WRITE_TAG;
import static com.kanedias.vanilla.plugins.PluginConstants.PREF_SDCARD_URI;

/**
 * Main worker of Plugin system. Handles all audio-file work, including loading and parsing audio file,
 * writing it through both filesystem and SAF, upgrade of outdated tags.
 *
 * @see PluginConstants
 * @see TagEditActivity
 *
 * @author Oleg Chernovskiy
 */
public class PluginTagWrapper {

    private SharedPreferences mPrefs;
    private SafPermissionHandler mSafHandler;

    private Activity mContext;
    private Intent mLaunchIntent;
    private AudioFile mAudioFile;
    private Tag mTag;

    private boolean waitingSafResponse = false;

    public PluginTagWrapper(Intent intent, Activity ctx) {
        mContext = ctx;
        mLaunchIntent = intent;

        mSafHandler = new SafPermissionHandler(mContext);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);

        TagOptionSingleton.getInstance().setAndroid(true);
    }

    public Tag getTag() {
        return mTag;
    }

    public boolean isWaitingSafResponse() {
        return waitingSafResponse;
    }

    /**
     * @see SafPermissionHandler#onActivityResult(int, int, Intent)
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mSafHandler.onActivityResult(requestCode, resultCode, data)) {
            waitingSafResponse = false;

            // continue persisting through SAF
            if (persistThroughSaf(data)) {
                mContext.finish();
            }
        }
    }

    /**
     * Loads file as {@link AudioFile} and performs initial tag creation if it's absent.
     * If error happens while loading, shows popup indicating error details.
     * @return true if and only if file was successfully read and initialized in tag system, false otherwise
     */
    public boolean loadFile(boolean force) {
        if (!force && mTag != null) {
            return true; // don't reload same file
        }

        // we need only path passed to us
        Uri fileUri = mLaunchIntent.getParcelableExtra(EXTRA_PARAM_URI);
        if (fileUri == null || fileUri.getPath() == null) {
            return false;
        }

        File file = new File(fileUri.getPath());
        if (!file.exists()) {
            return false;
        }

        try {
            mAudioFile = AudioFileIO.read(file);
            mTag = mAudioFile.getTagOrCreateAndSetDefault();
        } catch (CannotReadException | IOException | TagException | ReadOnlyFileException | InvalidAudioFrameException e) {
            Log.e(LOG_TAG,
                    String.format(mContext.getString(R.string.error_audio_file), file.getAbsolutePath()), e);
            Toast.makeText(mContext,
                    String.format(mContext.getString(R.string.error_audio_file) + ", %s",
                            file.getAbsolutePath(),
                            e.getLocalizedMessage()),
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    /**
     * upgrades ID3v2.x tag to ID3v2.4 for loaded file.
     * Call this method only if you know exactly that file contains ID3 tag.
     */
    public void upgradeID3v2() {
        mTag = mAudioFile.convertID3Tag((AbstractID3v2Tag) mTag, ID3V2Version.ID3_V24);
        mAudioFile.setTag(mTag);
        writeFile();
    }

    /**
     * Writes file to backing filesystem provider, this may be either SAF-managed sdcard or internal storage.
     * @return true if file was written successfully, false otherwise
     */
    public boolean writeFile() {
        if (SafUtils.isSafNeeded(mAudioFile.getFile(), mContext)) {
            if (mPrefs.contains(PREF_SDCARD_URI)) {
                // we already got the permission!
                return persistThroughSaf(null);
            }

            // request SAF permissions, handle in activity
            mSafHandler.handleFile(mAudioFile.getFile());
            waitingSafResponse = true;

            // we didn't write the file, don't close the window
            return false;
        } else {
            persistThroughFile();
            return true;
        }
    }

    /**
     * Writes changes in tags directly into file and closes activity.
     * Call this if you're absolutely sure everything is right with file and tag.
     */
    private void persistThroughFile() {
        try {
            AudioFileIO.write(mAudioFile);
            Toast.makeText(mContext, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();

            // update media database
            File persisted = mAudioFile.getFile();
            MediaScannerConnection.scanFile(mContext, new String[]{persisted.getAbsolutePath()}, null, null);
        } catch (CannotWriteException e) {
            Log.e(LOG_TAG,
                    String.format(mContext.getString(R.string.error_audio_file), mAudioFile.getFile().getPath()), e);
            Toast.makeText(mContext,
                    String.format(mContext.getString(R.string.error_audio_file) + ", %s",
                            mAudioFile.getFile().getPath(),
                            e.getLocalizedMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Write changes through SAF framework - the only way to do it in Android > 4.4 when working with SD card
     * @param safResponse response with URI contained in. Can be null if tree permission is already given.
     * @return true if file was written successfully, false otherwise
     */
    public boolean persistThroughSaf(Intent safResponse) {
        Uri safUri;
        if (mPrefs.contains(PREF_SDCARD_URI)) {
            // no sorcery can allow you to gain URI to the document representing file you've been provided with
            // you have to find it again using now Document API

            // /storage/volume/Music/some.mp3 will become [storage, volume, music, some.mp3]
            List<String> pathSegments = new ArrayList<>(Arrays.asList(mAudioFile.getFile().getAbsolutePath().split("/")));
            Uri allowedSdRoot = Uri.parse(mPrefs.getString(PREF_SDCARD_URI, ""));
            DocumentFile doc = DocumentFile.fromTreeUri(mContext, allowedSdRoot);
            if (doc == null) {
                // provided on API >= 21 but now on API 19? Shouldn't happen
                Toast.makeText(mContext, mContext.getString(R.string.saf_write_error) + allowedSdRoot, Toast.LENGTH_LONG).show();
                mPrefs.edit().remove(PREF_SDCARD_URI).apply();
                return false;
            }
            safUri = findInDocumentTree(doc, pathSegments);
        } else {
            safUri = safResponse.getData();
        }

        if (safUri == null) {
            // nothing selected or invalid file?
            Toast.makeText(mContext, R.string.saf_nothing_selected, Toast.LENGTH_LONG).show();
            return false;
        }

        try {
            // we don't have fd-related audiotagger write functions, have to use workaround
            // write audio file to temp cache dir
            // jaudiotagger can't work through file descriptor, sadly
            File original = mAudioFile.getFile();
            File temp = File.createTempFile("tmp-media", '.' + Utils.getExtension(original));
            Utils.copy(original, temp); // jtagger writes only header, we should copy the rest
            temp.deleteOnExit(); // in case of exception it will be deleted too
            mAudioFile.setFile(temp);
            AudioFileIO.write(mAudioFile);

            // retrieve FD from SAF URI
            ParcelFileDescriptor pfd = mContext.getContentResolver().openFileDescriptor(safUri, "rw");
            if (pfd == null) {
                // should not happen
                Log.e(LOG_TAG, "SAF provided incorrect URI!" + safUri);
                return false;
            }

            // now read persisted data and write it to real FD provided by SAF
            FileInputStream fis = new FileInputStream(temp);
            byte[] audioContent = PluginUtils.readFully(fis);
            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.write(audioContent);
            fos.close();

            // delete temporary file used
            if (!temp.delete()) {
                Log.w(LOG_TAG, "Couldn't delete temporary file: " + temp);
            }

            // rescan original file
            MediaScannerConnection.scanFile(mContext, new String[]{original.getAbsolutePath()}, null, null);
            Toast.makeText(mContext, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(mContext, mContext.getString(R.string.saf_write_error) + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            Log.e(LOG_TAG, "Failed to write to file descriptor provided by SAF!", e);
        }
        return true;
    }

    /**
     * Finds needed file through Document API for SAF. It's not optimized yet - you can still gain wrong URI on
     * files such as "/a/b/c.mp3" and "/b/a/c.mp3", but I consider it complete enough to be usable.
     * @param currentDir - document file representing current dir of search
     * @param remainingPathSegments - path segments that are left to find
     * @return URI for found file. Null if nothing found.
     */
    @Nullable
    private Uri findInDocumentTree(DocumentFile currentDir, List<String> remainingPathSegments) {
        for (DocumentFile file : currentDir.listFiles()) {
            int index = remainingPathSegments.indexOf(file.getName());
            if (index == -1) {
                continue;
            }

            if (file.isDirectory()) {
                remainingPathSegments.remove(file.getName());
                return findInDocumentTree(file, remainingPathSegments);
            }

            if (file.isFile() && index == remainingPathSegments.size() - 1) {
                // got to the last part
                return file.getUri();
            }
        }

        return null;
    }

    /**
     * This plugin also has P2P functionality with others. It provides generic way to
     * read and write tags for the file.
     * <br/>
     * If intent is passed with EXTRA_PARAM_P2P and READ then EXTRA_PARAM_P2P_KEY is considered
     * as an array of field keys to retrieve from file. The values read are written in the same order
     * into answer intent into EXTRA_PARAM_P2P_VAL.
     * <br/>
     * If intent is passed with EXTRA_PARAM_P2P and WRITE then EXTRA_PARAM_P2P_KEY is considered
     * as an array of field keys to write to file. EXTRA_PARAM_P2P_VAL represents values to be written in
     * the same order.
     *
     */
    public void handleP2pIntent() {
        String request = mLaunchIntent.getStringExtra(EXTRA_PARAM_P2P);
        switch (request) {
            case P2P_WRITE_TAG: {
                String[] fields = mLaunchIntent.getStringArrayExtra(EXTRA_PARAM_P2P_KEY);
                String[] values = mLaunchIntent.getStringArrayExtra(EXTRA_PARAM_P2P_VAL);
                for (int i = 0; i < fields.length; ++i) {
                    try {
                        FieldKey key = FieldKey.valueOf(fields[i]);
                        mTag.setField(key, values[i]);
                    } catch (IllegalArgumentException iae) {
                        Log.e(LOG_TAG, "Invalid tag requested: " + fields[i], iae);
                        Toast.makeText(mContext, R.string.invalid_tag_requested, Toast.LENGTH_SHORT).show();
                    } catch (FieldDataInvalidException e) {
                        // should not happen
                        Log.e(LOG_TAG, "Error writing tag", e);
                    }
                }
                writeFile();
                break;
            }
            case P2P_READ_TAG: {
                String[] fields = mLaunchIntent.getStringArrayExtra(EXTRA_PARAM_P2P_KEY);
                ApplicationInfo responseApp = mLaunchIntent.getParcelableExtra(EXTRA_PARAM_PLUGIN_APP);

                String[] values = new String[fields.length];
                for (int i = 0; i < fields.length; ++i) {
                    try {
                        FieldKey key = FieldKey.valueOf(fields[i]);
                        values[i] = mTag.getFirst(key);
                    } catch (IllegalArgumentException iae) {
                        Log.e(LOG_TAG, "Invalid tag requested: " + fields[i], iae);
                        Toast.makeText(mContext, R.string.invalid_tag_requested, Toast.LENGTH_SHORT).show();
                    }
                }

                Intent response = new Intent(ACTION_LAUNCH_PLUGIN);
                response.putExtra(EXTRA_PARAM_P2P, P2P_READ_TAG);
                response.putExtra(EXTRA_PARAM_P2P_VAL, values);
                response.putExtras(mLaunchIntent); // return back everything we've got
                response.setPackage(responseApp.packageName);
                mContext.startActivity(response);
                break;
            }
            case P2P_READ_ART: {
                ApplicationInfo responseApp = mLaunchIntent.getParcelableExtra(EXTRA_PARAM_PLUGIN_APP);
                Artwork cover = mTag.getFirstArtwork();
                Uri uri = null;
                try {
                    if (cover == null) {
                        Log.w(LOG_TAG, "Artwork is not present for file " + mAudioFile.getFile().getName());
                        break;
                    }

                    File coversDir = new File(mContext.getCacheDir(), "covers");
                    if (!coversDir.exists() && !coversDir.mkdir()) {
                        Log.e(LOG_TAG, "Couldn't create dir for covers! Path " + mContext.getCacheDir());
                        break;
                    }

                    // cleanup old images
                    for (File oldImg : coversDir.listFiles()) {
                        if (!oldImg.delete()) {
                            Log.w(LOG_TAG, "Couldn't delete old image file! Path " + oldImg);
                        }
                    }

                    // write artwork to file
                    File coverTmpFile = new File(coversDir, UUID.randomUUID().toString());
                    FileOutputStream fos = new FileOutputStream(coverTmpFile);
                    fos.write(cover.getBinaryData());
                    fos.close();

                    // create sharable uri
                    uri = FileProvider.getUriForFile(mContext, BuildConfig.APPLICATION_ID + ".fileprovider", coverTmpFile);
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Couldn't write to cache file", e);
                    Toast.makeText(mContext, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
                } finally {
                    // share uri if created successfully
                    Intent response = new Intent(ACTION_LAUNCH_PLUGIN);
                    response.putExtra(EXTRA_PARAM_P2P, P2P_READ_ART);
                    response.putExtras(mLaunchIntent); // return back everything we've got
                    response.setPackage(responseApp.packageName);
                    if (uri != null) {
                        mContext.grantUriPermission(responseApp.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        response.putExtra(EXTRA_PARAM_P2P_VAL, uri);
                    }
                    mContext.startActivity(response);
                }
                break;
            }
            case P2P_WRITE_ART: {
                Uri imgLink = mLaunchIntent.getParcelableExtra(EXTRA_PARAM_P2P_VAL);

                try {
                    ParcelFileDescriptor pfd = mContext.getContentResolver().openFileDescriptor(imgLink, "r");
                    if (pfd == null) {
                        return;
                    }

                    FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
                    byte[] imgBytes = PluginUtils.readFully(fis);

                    Artwork cover = new AndroidArtwork();
                    cover.setBinaryData(imgBytes);
                    cover.setMimeType(ImageFormats.getMimeTypeForBinarySignature(imgBytes));
                    cover.setDescription("");
                    cover.setPictureType(PictureTypes.DEFAULT_ID);

                    mTag.deleteArtworkField();
                    mTag.setField(cover);
                } catch (IOException | IllegalArgumentException | FieldDataInvalidException e) {
                    Log.e(LOG_TAG, "Invalid artwork!", e);
                    Toast.makeText(mContext, R.string.invalid_artwork_provided, Toast.LENGTH_SHORT).show();
                }

                writeFile();
                break;
            }
        }

    }
}
