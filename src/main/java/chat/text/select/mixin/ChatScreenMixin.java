package chat.text.select.mixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
    protected ChatScreenMixin() { super(null); }
    @Shadow private ChatComponent.DisplayMode displayMode;
    @Unique private int chatSelect$startIdx = -1;
    @Unique private int chatSelect$endIdx = -1;
    @Unique private int chatSelect$startCharIdx = 0;
    @Unique private int chatSelect$endCharIdx = 0;
    @Unique private boolean chatSelect$dragging = false;
    @Unique private static final int chatSelect$LEFT_BUTTON = 1;
    @Unique
    private record LineMetrics(List<Integer> codePoints, float[] cumulativeWidth) {
        int charCount() { return codePoints.size(); }
        float widthTo(int idx) { return cumulativeWidth[Mth.clamp(idx, 0, codePoints.size())]; }
    }
    @Unique
    private static LineMetrics chatSelect$buildMetrics(FormattedCharSequence content, Font font) {
        List<Integer> codePoints = new ArrayList<>();
        List<Style> styles = new ArrayList<>();
        content.accept((index, style, codePoint) -> {
            codePoints.add(codePoint);
            styles.add(style);
            return true;
        });
        float[] cumulative = new float[codePoints.size() + 1];
        for (int i = 0; i < codePoints.size(); i++) {
            float width = font.getSplitter().stringWidth(FormattedCharSequence.codepoint(codePoints.get(i), styles.get(i)));
            cumulative[i + 1] = cumulative[i] + width;
        }
        return new LineMetrics(codePoints, cumulative);
    }
    @Unique
    private static int chatSelect$charIndexAtX(LineMetrics metrics, double localX) {
        int count = metrics.charCount();
        for (int i = 0; i < count; i++) {
            float mid = (metrics.cumulativeWidth()[i] + metrics.cumulativeWidth()[i + 1]) / 2.0f;
            if (localX < mid) return i;
        }
        return count;
    }
    @Unique
    private int chatSelect$screenYToTrimmedIndex(double screenY) {
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.options.chatScale().get();
        double spacing = mc.options.chatLineSpacing().get();
        int entryHeight = (int) (9.0 * (spacing + 1.0));
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottomLocal = Mth.floor((screenHeight - 40) / (float) scale);
        double localY = screenY / scale;
        int displayIdx = (int) Math.floor((chatBottomLocal - localY) / entryHeight);
        int scroll = ((ChatComponentAccessor) mc.gui.hud.getChat()).chattextselect$getChatScrollbarPos();
        return displayIdx + scroll;
    }
    @Unique
    private double chatSelect$screenXToLocalX(double screenX, float lineX) {
        double scale = Minecraft.getInstance().options.chatScale().get();
        return screenX / scale - lineX;
    }
    @Unique
    private Map<FormattedCharSequence, Float> chatSelect$lineXs() {
        Minecraft mc = Minecraft.getInstance();
        Map<FormattedCharSequence, Float> result = new IdentityHashMap<>();
        Matrix3x2f pose = new Matrix3x2f();
        ChatComponent.ChatGraphicsAccess recorder = new ChatComponent.ChatGraphicsAccess() {
            @Override
            public void updatePose(Consumer<Matrix3x2f> modifier) { modifier.accept(pose); }
            @Override
            public void fill(int x0, int y0, int x1, int y1, int color) {}
            @Override
            public boolean handleMessage(int y, float opacity, FormattedCharSequence content) {
                result.put(content, pose.m20() / pose.m00());
                return false;
            }
            @Override
            public void handleTag(int x0, int y0, int x1, int y1, float opacity, GuiMessageTag tag) {}
            @Override
            public void handleTagIcon(int x, int y, boolean hovered, GuiMessageTag tag, GuiMessageTag.Icon icon) {}
        };
        ((ChatComponentAccessor) mc.gui.hud.getChat()).chattextselect$extractRenderState(
                recorder, mc.getWindow().getGuiScaledHeight(), mc.gui.hud.getGuiTicks(), this.displayMode);
        return result;
    }
    @Unique
    private static float chatSelect$lineX(Map<FormattedCharSequence, Float> lineXs, GuiMessage.Line line) {
        return lineXs.getOrDefault(line.content(), 4.0f);
    }
    @Unique
    private Style chatSelect$clickableStyleAt(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        ActiveTextCollector.ClickableStyleFinder finder =
                new ActiveTextCollector.ClickableStyleFinder(getFont(), (int) screenX, (int) screenY)
                        .includeInsertions(false);
        mc.gui.hud.getChat().captureClickableText(finder, screenHeight, mc.gui.hud.getGuiTicks(), this.displayMode);
        Style style = finder.result();
        return (style != null && style.getClickEvent() != null) ? style : null;
    }
    @Unique
    private boolean chatSelect$isInChatHistory(double screenX, double screenY) {
        Minecraft mc = Minecraft.getInstance();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        if (screenY >= screenHeight - 40) return false;
        double scale = mc.options.chatScale().get();
        int chatWidthScreen = (int) (ChatComponent.getWidth((Double) mc.options.chatWidth().get()) * scale);
        return screenX >= 0 && screenX <= chatWidthScreen + 8;
    }
    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void chatSelect$onMouseClicked(MouseButtonEvent event, boolean doubleClick,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (event.button() != chatSelect$LEFT_BUTTON) return;
        if (chatSelect$clickableStyleAt(event.x(), event.y()) != null) {
            chatSelect$startIdx = -1;
            chatSelect$endIdx = -1;
            chatSelect$dragging = false;
            return;
        }
        if (chatSelect$isInChatHistory(event.x(), event.y())) {
            List<GuiMessage.Line> lines = ((ChatComponentAccessor) Minecraft.getInstance().gui.hud.getChat())
                    .chattextselect$getTrimmedMessages();
            int idx = chatSelect$screenYToTrimmedIndex(event.y());
            if (idx >= 0 && idx < lines.size()) {
                GuiMessage.Line line = lines.get(idx);
                LineMetrics metrics = chatSelect$buildMetrics(line.content(), getFont());
                float lineX = chatSelect$lineX(chatSelect$lineXs(), line);
                int charIdx = chatSelect$charIndexAtX(metrics, chatSelect$screenXToLocalX(event.x(), lineX));
                chatSelect$startIdx = idx;
                chatSelect$endIdx = idx;
                chatSelect$startCharIdx = charIdx;
                chatSelect$endCharIdx = charIdx;
                chatSelect$dragging = true;
                return;
            }
        }
        chatSelect$startIdx = -1;
        chatSelect$endIdx = -1;
        chatSelect$dragging = false;
    }
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (chatSelect$dragging && event.button() == chatSelect$LEFT_BUTTON) {
            List<GuiMessage.Line> lines = ((ChatComponentAccessor) Minecraft.getInstance().gui.hud.getChat())
                    .chattextselect$getTrimmedMessages();
            if (lines.isEmpty()) return true;
            int idx = Mth.clamp(chatSelect$screenYToTrimmedIndex(event.y()), 0, lines.size() - 1);
            GuiMessage.Line line = lines.get(idx);
            LineMetrics metrics = chatSelect$buildMetrics(line.content(), getFont());
            float lineX = chatSelect$lineX(chatSelect$lineXs(), line);
            chatSelect$endIdx = idx;
            chatSelect$endCharIdx = chatSelect$charIndexAtX(metrics, chatSelect$screenXToLocalX(event.x(), lineX));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == chatSelect$LEFT_BUTTON) {
            chatSelect$dragging = false;
        }
        return super.mouseReleased(event);
    }
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void chatSelect$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!event.isCopy()) return;
        if (chatSelect$startIdx < 0 || chatSelect$endIdx < 0) return;
        Minecraft mc = Minecraft.getInstance();
        List<GuiMessage.Line> lines = ((ChatComponentAccessor) mc.gui.hud.getChat())
                .chattextselect$getTrimmedMessages();
        if (lines.isEmpty()) return;
        int lo = Mth.clamp(Math.min(chatSelect$startIdx, chatSelect$endIdx), 0, lines.size() - 1);
        int hi = Mth.clamp(Math.max(chatSelect$startIdx, chatSelect$endIdx), 0, lines.size() - 1);
        int topChar = chatSelect$startIdx >= chatSelect$endIdx ? chatSelect$startCharIdx : chatSelect$endCharIdx;
        int bottomChar = chatSelect$startIdx >= chatSelect$endIdx ? chatSelect$endCharIdx : chatSelect$startCharIdx;
        if (hi == lo) {
            int a = Math.min(topChar, bottomChar);
            int b = Math.max(topChar, bottomChar);
            topChar = a;
            bottomChar = b;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = hi; i >= lo; i--) {
            LineMetrics metrics = chatSelect$buildMetrics(lines.get(i).content(), getFont());
            int from = (i == hi) ? Mth.clamp(topChar, 0, metrics.charCount()) : 0;
            int to = (i == lo) ? Mth.clamp(bottomChar, 0, metrics.charCount()) : metrics.charCount();
            for (int c = from; c < to; c++) {
                sb.appendCodePoint(metrics.codePoints().get(c));
            }
            if (i > lo) {
                sb.append(lines.get(i).endOfEntry() ? "\n" : " ");
            }
        }
        mc.keyboardHandler.setClipboard(sb.toString());
        cir.setReturnValue(true);
        cir.cancel();
    }
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void chatSelect$onRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                     float delta, CallbackInfo ci) {
        if (chatSelect$startIdx < 0 || chatSelect$endIdx < 0) return;
        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.hud.getChat();
        List<GuiMessage.Line> lines = ((ChatComponentAccessor) chat).chattextselect$getTrimmedMessages();
        if (lines.isEmpty()) return;
        int scroll = ((ChatComponentAccessor) chat).chattextselect$getChatScrollbarPos();
        float scale = (float) (double) mc.options.chatScale().get();
        int entryHeight = (int) (9.0 * ((double) mc.options.chatLineSpacing().get() + 1.0));
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottomLocal = Mth.floor((screenHeight - 40) / scale);
        int linesPerPage = chat.getLinesPerPage();
        int maxWidth = Mth.ceil(ChatComponent.getWidth((Double) mc.options.chatWidth().get()) / scale);
        int lo = Math.min(chatSelect$startIdx, chatSelect$endIdx);
        int hi = Math.max(chatSelect$startIdx, chatSelect$endIdx);
        int topChar = chatSelect$startIdx >= chatSelect$endIdx ? chatSelect$startCharIdx : chatSelect$endCharIdx;
        int bottomChar = chatSelect$startIdx >= chatSelect$endIdx ? chatSelect$endCharIdx : chatSelect$startCharIdx;
        if (hi == lo) {
            int a = Math.min(topChar, bottomChar);
            int b = Math.max(topChar, bottomChar);
            topChar = a;
            bottomChar = b;
        }
        int highlightColor = ARGB.color(100, 0, 120, 215);
        Font font = getFont();
        Map<FormattedCharSequence, Float> lineXs = chatSelect$lineXs();
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        for (int tmIdx = lo; tmIdx <= hi; tmIdx++) {
            if (tmIdx < 0 || tmIdx >= lines.size()) continue;
            int displayIdx = tmIdx - scroll;
            if (displayIdx < 0 || displayIdx >= linesPerPage) continue;
            int entryBottom = chatBottomLocal - displayIdx * entryHeight;
            int entryTop = entryBottom - entryHeight;
            GuiMessage.Line line = lines.get(tmIdx);
            float lineX = chatSelect$lineX(lineXs, line);
            float xStart;
            float xEnd;
            if (hi == lo) {
                LineMetrics metrics = chatSelect$buildMetrics(line.content(), font);
                xStart = lineX + metrics.widthTo(topChar);
                xEnd = lineX + metrics.widthTo(bottomChar);
            } else if (tmIdx == hi) {
                LineMetrics metrics = chatSelect$buildMetrics(line.content(), font);
                xStart = lineX + metrics.widthTo(topChar);
                xEnd = maxWidth + 12;
            } else if (tmIdx == lo) {
                LineMetrics metrics = chatSelect$buildMetrics(line.content(), font);
                xStart = 0;
                xEnd = lineX + metrics.widthTo(bottomChar);
            } else {
                xStart = 0;
                xEnd = maxWidth + 12;
            }
            graphics.fill((int) xStart, entryTop, (int) xEnd, entryBottom, highlightColor);
        }
        graphics.pose().popMatrix();
    }
}
