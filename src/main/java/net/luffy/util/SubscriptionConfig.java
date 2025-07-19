package net.luffy.util;

import java.util.*;

/**
 * 监控订阅配置类
 * 支持按QQ群组管理订阅成员列表
 */
public class SubscriptionConfig {
    private long qqGroup;
    private Set<String> memberSubs;
    
    public SubscriptionConfig() {
        this.memberSubs = new HashSet<>();
    }
    
    public SubscriptionConfig(long qqGroup, Set<String> memberSubs) {
        this.qqGroup = qqGroup;
        this.memberSubs = memberSubs != null ? new HashSet<>(memberSubs) : new HashSet<>();
    }
    
    public long getQqGroup() {
        return qqGroup;
    }
    
    public void setQqGroup(long qqGroup) {
        this.qqGroup = qqGroup;
    }
    
    public Set<String> getMemberSubs() {
        return memberSubs;
    }
    
    public void setMemberSubs(Set<String> memberSubs) {
        this.memberSubs = memberSubs != null ? new HashSet<>(memberSubs) : new HashSet<>();
    }
    
    public void addMember(String memberName) {
        if (memberName != null && !memberName.trim().isEmpty()) {
            this.memberSubs.add(memberName.trim());
        }
    }
    
    public boolean removeMember(String memberName) {
        if (memberName != null && !memberName.trim().isEmpty()) {
            return this.memberSubs.remove(memberName.trim());
        }
        return false;
    }
    
    public boolean hasMember(String memberName) {
        if (memberName != null && !memberName.trim().isEmpty()) {
            return this.memberSubs.contains(memberName.trim());
        }
        return false;
    }
    
    public int getMemberCount() {
        return this.memberSubs.size();
    }
    
    public boolean isEmpty() {
        return this.memberSubs.isEmpty();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SubscriptionConfig that = (SubscriptionConfig) obj;
        return qqGroup == that.qqGroup;
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(qqGroup);
    }
    
    @Override
    public String toString() {
        return String.format("SubscriptionConfig{qqGroup=%d, memberSubs=%s}", qqGroup, memberSubs);
    }
}