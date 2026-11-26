package com.novum.tv.data.repository

/** 服务器返回的人物引用：名称、作品页 id 与头像地址。 */
data class PersonRef(
    val name: String,
    val personId: String,
    val imageUrl: String? = null,
)
