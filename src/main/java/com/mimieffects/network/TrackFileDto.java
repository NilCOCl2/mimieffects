package com.mimieffects.network;

/**
 * One entry in the list sent by TracksSyncPayload: a track config file's
 * name and its raw JSON text, exactly as it sits on disk. Kept as raw
 * text (not a parsed TrackConfig) so the wire format never has to change
 * when TrackConfig's schema does — only Gson on each end needs to agree.
 */
public class TrackFileDto {
    public String fileName;
    public String json;

    public TrackFileDto() {
        // for Gson
    }

    public TrackFileDto(String fileName, String json) {
        this.fileName = fileName;
        this.json = json;
    }
}
