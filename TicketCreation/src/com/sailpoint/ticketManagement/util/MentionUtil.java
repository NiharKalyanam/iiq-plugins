package com.sailpoint.ticketManagement.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MentionUtil {

	private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_\\-]+(?:\\s[A-Za-z0-9_\\-]+)?)");

    public static List<String> extractMentions(String text) {
        List<String> mentions = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return mentions;
        }

        Matcher matcher = MENTION_PATTERN.matcher(text);
        while (matcher.find()) {
            mentions.add(matcher.group(1).trim());
        }
        return mentions;
    }
}
