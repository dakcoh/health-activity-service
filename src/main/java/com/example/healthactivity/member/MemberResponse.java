package com.example.healthactivity.member;

public record MemberResponse(Long id, String name, String nickname, String email) {
    public static MemberResponse from(Member member) {
        return new MemberResponse(member.getId(), member.getName(), member.getNickname(), member.getEmail());
    }
}
