/*
 * Headwind Remote: Open Source Remote Access Software for Android
 * https://headwind-remote.com
 *
 * Copyright (C) 2022 headwind-remote.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.control.janus;

import android.content.Context;
import android.util.Log;

import com.hmdm.control.Const;
import com.hmdm.control.ServerApiHelper;
import com.hmdm.control.janus.json.JanusMessageRequest;
import com.hmdm.control.janus.json.JanusPollResponse;
import com.hmdm.control.janus.json.JanusResponse;
import com.hmdm.control.janus.json.JanusStreamingCreateRequest;
import com.hmdm.control.janus.json.JanusStreamingCreateResponse;

import retrofit2.Response;

public class JanusStreamingPlugin extends JanusPlugin {
    private String streamingId;
    private String password;
    private int audioPort;
    private int videoPort;

    @Override
    public String getName() {
        return Const.JANUS_PLUGIN_STREAMING;
    }

    @Override
    public void onWebRtcUp(final Context context) {
    }

    @Override
    public void onPollingEvent(JanusPollResponse event) {
    }

    public int create(String streamingId, String password, boolean audio) {
        this.streamingId = streamingId;
        this.password = password;

        JanusStreamingCreateRequest createRequest = new JanusStreamingCreateRequest(secret, "message", sessionId, getHandleId());
        JanusStreamingCreateRequest.Body body = new JanusStreamingCreateRequest.Body();
        body.setRequest("create");
        body.setType("rtp");
        body.setDescription(streamingId);
        body.setPermanent(false);
        body.setIs_private(false);
        body.setAudio(audio);
        body.setVideo(true);
        body.setData(false);
        body.setId(streamingId);
        body.setName(streamingId);
        body.setPin(password);
        if (audio) {
            body.setAudioport(0);
            body.setAudiopt(111);
            body.setAudiortpmap("opus/48000/2");
        }
        body.setVideoport(0);
        body.setVideopt(100);
        body.setVideortpmap("H264/90000");
        body.setVideobufferkf(false);
        // Magic to make the video working in Firefox and Safari
        // https://stackoverflow.com/questions/22960928/identify-h264-profile-and-level-from-profile-level-id-in-sdp
        // https://stackoverflow.com/questions/23494168/h264-profile-iop-explained
        // profile_idc 0x42 = 66 = Baseline profile
        // profile-iop 0xe0 = constraint_set0_flag=1, constraint_set1_flag=1, constraint_set2_flag=1, constraint_set3_flag=0
        // 1f = 31 = level 3.1
        body.setVideofmtp("profile-level-id=42e01f;packetization-mode=1");
        createRequest.setBody(body);

        Response<JanusStreamingCreateResponse> response = ServerApiHelper.execute(apiInstance.createStreaming(sessionId, getHandleId(), createRequest), "create streaming");
        if (response == null) {
            errorReason = "Network error";
            return Const.NETWORK_ERROR;
        }
        if (response.body() != null && response.body().getJanus().equalsIgnoreCase("success") && response.body().getPlugindata() != null) {
            JanusStreamingCreateResponse.StreamingData data = response.body().getPlugindata().getData();
            if (data == null || data.getStream() == null) {
                errorReason = "Server error";
                Log.w(Const.LOG_TAG, "Wrong server response: " + response.body().toString());
                return Const.SERVER_ERROR;
            }
            audioPort = data.getStream().getAudio_port();
            videoPort = data.getStream().getVideo_port();
            Log.i(Const.LOG_TAG, "Stream created, audioport=" + audioPort + ", videoport=" + videoPort);
        } else {
            errorReason = "Server error";
            Log.w(Const.LOG_TAG, "Wrong server response: " + response.body().toString());
            return Const.SERVER_ERROR;
        }
        return Const.SUCCESS;
    }

    @Override
    public int destroy() {
        JanusMessageRequest destroyRequest = new JanusMessageRequest(secret, "message", sessionId, getHandleId());
        destroyRequest.setBody(new JanusMessageRequest.Body("destroy", streamingId));
        destroyRequest.generateTransactionId();

        Response<JanusResponse> response = ServerApiHelper.execute(apiInstance.sendMessage(sessionId, getHandleId(), destroyRequest), "destroy streaming");
        if (response == null) {
            errorReason = "Network error";
            return Const.NETWORK_ERROR;
        }
        if (response.body() == null || !response.body().getJanus().equalsIgnoreCase("success")) {
            errorReason = "Server error";
            Log.w(Const.LOG_TAG, "Wrong server response: " + response.body().toString());
            return Const.SERVER_ERROR;
        }
        return Const.SUCCESS;
    }

    public String getStreamingId() {
        return streamingId;
    }

    public int getAudioPort() {
        return audioPort;
    }

    public int getVideoPort() {
        return videoPort;
    }
}
