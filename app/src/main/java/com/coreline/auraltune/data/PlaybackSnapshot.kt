// PlaybackSnapshot.kt
// 플레이어 큐 + 현재 곡/위치를 앱 재시작 후 복원하기 위한 직렬화 스냅샷.
// SAF content:// URI를 문자열로 보관한다 — 재시작 후 읽으려면 선택 시점에
// takePersistableUriPermission으로 영속 읽기 권한을 받아야 한다(MusicPlayerController).
package com.coreline.auraltune.data

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackTrack(val uri: String, val title: String)

@Serializable
data class PlaybackSnapshot(
    val tracks: List<PlaybackTrack> = emptyList(),
    /** 현재 곡 인덱스. */
    val index: Int = 0,
    /** 현재 곡 재생 위치(ms). */
    val positionMs: Long = 0L,
)
