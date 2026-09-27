package com.hospital.pbb.oplog;

/** 操作日志的动作名称，全系统统一从这里取，不要在调用处写字符串字面量。 */
public final class OpAction {

    public static final String LOGIN = "登录";
    public static final String LOGIN_FAIL = "登录失败";
    public static final String LOGOUT = "登出";
    public static final String CHANGE_PASSWORD = "修改密码";
    public static final String RESET_PASSWORD = "重置密码";
    public static final String UNLOCK_USER = "解锁账号";
    public static final String DISABLE_USER = "停用账号";
    public static final String ENABLE_USER = "启用账号";
    public static final String CREATE_USER = "新增账号";
    public static final String CREATE_STAFF = "新增人员";
    public static final String UPDATE_STAFF = "修改人员";
    public static final String SORT_STAFF = "调整人员排序";
    public static final String UPDATE_SHIFT = "修改班次";
    public static final String CREATE_HOLIDAY = "新增节假日";
    public static final String UPDATE_HOLIDAY = "修改节假日";
    public static final String DELETE_HOLIDAY = "删除节假日";
    public static final String COPY_HOLIDAY = "复制节假日";

    public static final String UPDATE_SCHEDULE = "修改排班";
    public static final String GENERATE_SCHEDULE = "按规则生成排班";
    public static final String PUBLISH_SCHEDULE = "发布排班";
    public static final String APPLY_SWAP_TO_SCHEDULE = "调班回写排班";
    public static final String CREATE_SWAP = "发起调班";
    public static final String CONFIRM_SWAP = "同意调班";
    public static final String REJECT_SWAP_PEER = "拒绝调班";
    public static final String CANCEL_SWAP = "撤销调班";
    public static final String APPROVE_SWAP = "审批通过调班";
    public static final String REJECT_SWAP = "审批驳回调班";

    private OpAction() {
    }
}
