package com.vaca.callmate.data

fun getOnboardingScripts(lang: Language): List<OnboardingScript> = when (lang) {
    Language.Zh -> listOf(
        OnboardingScript(
            stepId = 0,
            aiQuestions = listOf(
                "老板好！我是您的实习 AI 助理。虽然我才刚入职，但我会努力帮您挡掉骚扰电话的！" to 4000,
                "为了不给您惹麻烦，遇到【快递外卖】打来问地址时，您希望我怎么回复比较好？" to 4000
            ),
            topic = "快递/外卖",
            simulatedUserReply = "帮我跟他说，直接放在门口的鞋柜上就行。",
            strategy = StrategyData(
                trigger = "识别到“送快递/外卖”意图",
                action = "回复：“请直接放在门口鞋柜上。”"
            )
        ),
        OnboardingScript(
            stepId = 1,
            aiQuestions = listOf(
                "收到，记下来了！我会告诉他们放门口鞋柜。👇" to 2500,
                "那如果遇到【推销或者骚扰电话】，您是希望我礼貌拒绝，还是直接挂断呢？" to 3500
            ),
            topic = "推销/骚扰",
            simulatedUserReply = "这种直接挂断吧，不需要通知我。",
            strategy = StrategyData(
                trigger = "识别到推销/骚扰意图",
                action = "礼貌拒绝并自动挂断"
            )
        )
    )
    Language.En -> listOf(
        OnboardingScript(
            stepId = 0,
            aiQuestions = listOf(
                "Hello Boss! I'm your intern AI. I'll do my best to block spam calls!" to 4000,
                "For delivery calls asking for location, how should I reply?" to 4000
            ),
            topic = "Delivery",
            simulatedUserReply = "Tell them to leave it at the door.",
            strategy = StrategyData(
                trigger = "Intent: Delivery",
                action = "Reply: Leave at door."
            )
        ),
        OnboardingScript(
            stepId = 1,
            aiQuestions = listOf(
                "Got it! I'll note that down. 👇" to 2500,
                "What about spam or sales calls? Decline politely or hang up?" to 3500
            ),
            topic = "Spam",
            simulatedUserReply = "Just hang up, don't notify me.",
            strategy = StrategyData(
                trigger = "Intent: Spam",
                action = "Decline & Auto Hangup"
            )
        )
    )
}

private val MOCK_TRANSCRIPT_ZH = listOf(
    ChatMessage(1, MessageSender.Caller, "美团外卖来电。外卖，我在电梯了，麻烦来取一下。", startTime = 0, endTime = 5),
    ChatMessage(2, MessageSender.Ai, "你送到哪里了？", startTime = 6, endTime = 8),
    ChatMessage(3, MessageSender.Caller, "软基8楼十方融海，是你吗？", startTime = 9, endTime = 13),
    ChatMessage(4, MessageSender.Ai, "嗯，是的，放到外面的架子上就行。", startTime = 14, endTime = 18),
    ChatMessage(5, MessageSender.Caller, "好的", startTime = 19, endTime = 20)
)

private val MOCK_TRANSCRIPT_EN = listOf(
    ChatMessage(1, MessageSender.Caller, "Meituan Delivery. I am at the elevator, please pick up.", startTime = 0, endTime = 5),
    ChatMessage(2, MessageSender.Ai, "Where are you exactly?", startTime = 6, endTime = 8),
    ChatMessage(3, MessageSender.Caller, "Software Base 8F, is that you?", startTime = 9, endTime = 13),
    ChatMessage(4, MessageSender.Ai, "Yes, just leave it on the shelf outside.", startTime = 14, endTime = 18),
    ChatMessage(5, MessageSender.Caller, "Okay.", startTime = 19, endTime = 20)
)

fun getMockCalls(lang: Language): List<CallRecord> = when (lang) {
    Language.Zh -> listOf(
        CallRecord(
            id = 1,
            phone = "13625647829",
            label = "美团外卖",
            time = "今天 17:25",
            status = CallStatus.Handled,
            summary = "外卖已放到8楼架子",
            fullSummary = "美团外卖来电。外卖，我在电梯了，麻烦来取一下...",
            transcript = MOCK_TRANSCRIPT_ZH,
            duration = 21
        ),
        CallRecord(
            id = 2,
            phone = "13625647829",
            label = "顺丰快递",
            time = "今天 16:25",
            status = CallStatus.Handled,
            summary = "快递已放门口",
            fullSummary = "快递员确认家中无人，已放门口。",
            duration = 15
        ),
        CallRecord(
            id = 3,
            phone = "13625647829",
            label = "未知号码",
            time = "星期一 09:25",
            status = CallStatus.Blocked,
            summary = "已挂断房产推销",
            fullSummary = "识别为高频骚扰号码，已自动挂断。",
            duration = 5
        )
    )
    Language.En -> listOf(
        CallRecord(
            id = 1,
            phone = "13625647829",
            label = "Meituan",
            time = "Today 17:25",
            status = CallStatus.Handled,
            summary = "Food left at 8F shelf",
            fullSummary = "Delivery driver at elevator, instructed to leave on shelf.",
            transcript = MOCK_TRANSCRIPT_EN,
            duration = 21
        ),
        CallRecord(
            id = 2,
            phone = "13625647829",
            label = "SF Express",
            time = "Today 16:25",
            status = CallStatus.Handled,
            summary = "Package at door",
            fullSummary = "Courier confirmed nobody home, left at door.",
            duration = 15
        ),
        CallRecord(
            id = 3,
            phone = "13625647829",
            label = "Unknown",
            time = "Mon 09:25",
            status = CallStatus.Blocked,
            summary = "Blocked Spam",
            fullSummary = "Identified as spam, auto-blocked.",
            duration = 5
        )
    )
}
