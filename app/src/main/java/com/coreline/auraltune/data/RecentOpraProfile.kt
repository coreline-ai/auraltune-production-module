// RecentOpraProfile.kt
// OPRA 탭의 '최근 선택' 기록 항목. OpraCatalogEntry는 opra-data 모듈에 있고 @Serializable이 아니므로,
// app 모듈에서 빠른선택에 필요한 최소 정보(id + 표시 이름)만 직렬화해 SettingsStore에 보관한다.
// 재선택 시 id로 opraRepository.resolveById → toAutoEqProfile 하여 같은 엔진에 적용한다.
package com.coreline.auraltune.data

import kotlinx.serialization.Serializable

@Serializable
data class RecentOpraProfile(
    val id: String,
    val name: String,
)
