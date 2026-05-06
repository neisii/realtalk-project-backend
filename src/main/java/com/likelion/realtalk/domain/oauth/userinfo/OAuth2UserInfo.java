package com.likelion.realtalk.domain.oauth.userinfo;

public abstract class OAuth2UserInfo {

    public abstract String getProviderId();

    public abstract String getProvider();

    public abstract String getEmail();

    public abstract String getNickname();
}
