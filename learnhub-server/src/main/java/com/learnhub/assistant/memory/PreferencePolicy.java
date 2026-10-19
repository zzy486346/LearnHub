package com.learnhub.assistant.memory;

import com.learnhub.common.exception.BusinessException;
import java.util.Set;
import java.util.regex.Pattern;

/** 长期保存仅限学习偏好；分类标签不能代替正文校验。 */
public final class PreferencePolicy {
    private static final Set<String> CATEGORIES = Set.of("STYLE", "PACE", "LEVEL", "GOAL");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(密码|口令|私钥|密钥|令牌|身份证|银行卡|支付|订单|姓名|住址|地址|邮箱|电话|手机号|完整材料|讲义全文|忽略.{0,8}指令|系统提示|system\\s*prompt|password|secret|token|api[_ -]?key|BEGIN.{0,20}PRIVATE KEY|sk-[a-z0-9_-]{8,}|gh[pousr]_[a-z0-9]{12,}|github_pat_[a-z0-9_]{12,}|AKIA[A-Z0-9]{16}|eyJ[a-z0-9_-]{8,}\\.[a-z0-9_-]+\\.[a-z0-9_-]+|bearer\\s+\\S+|1[3-9]\\d{9}|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|\\d{17}[\\dxX])");

    private PreferencePolicy() { }

    public static String validate(String category, String text) {
        if (!CATEGORIES.contains(category == null ? "" : category) || text == null)
            throw invalid();
        String clean = text.strip();
        if (clean.isEmpty() || clean.length() > 200 || clean.codePoints().anyMatch(Character::isISOControl)
                || SENSITIVE.matcher(clean).find()) throw invalid();
        for (int i = 0; i < clean.length(); i++) {
            char value = clean.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i >= clean.length() || !Character.isLowSurrogate(clean.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(value)) throw invalid();
        }
        return clean;
    }

    private static BusinessException invalid() {
        return new BusinessException("MEMORY_INPUT_INVALID", "仅支持1至200字的学习偏好，请移除敏感信息、材料正文或指令");
    }
}
