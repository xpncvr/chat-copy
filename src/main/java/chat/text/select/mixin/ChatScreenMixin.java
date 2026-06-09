package chat.text.select.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {

    protected ChatScreenMixin() { super(Text.empty()); }

    @Unique private int chatSelect$startIdx = -1;
    @Unique private int chatSelect$endIdx = -1;
    @Unique private boolean chatSelect$dragging = false;

    @Unique
    private int chatSelect$screenYToVisibleIndex(double screenY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double scale = (Double) mc.options.getChatScale().getValue();
        double spacing = (Double) mc.options.getChatLineSpacing().getValue();
        int entryHeight = (int) (9.0 * (spacing + 1.0));
        int screenHeight = mc.getWindow().getScaledHeight();
        int chatBottomLocal = MathHelper.floor((screenHeight - 40) / (float) scale);
        double localY = screenY / scale;
        int displayIdx = (int) Math.floor((chatBottomLocal - localY) / entryHeight);
        int scroll = ((ChatHudAccessor) mc.inGameHud.getChatHud()).chattextselect$getScrolledLines();
        return displayIdx + scroll;
    }

    @Unique
    private boolean chatSelect$isInChatHistory(double screenX, double screenY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int screenHeight = mc.getWindow().getScaledHeight();
        if (screenY >= screenHeight - 40) return false;
        double scale = (Double) mc.options.getChatScale().getValue();
        int chatWidthScreen = (int) (ChatHud.getWidth((Double) mc.options.getChatWidth().getValue()) * scale);
        return screenX >= 0 && screenX <= chatWidthScreen + 8;
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void chatSelect$onMouseClicked(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() != 0) return;

        if (chatSelect$isInChatHistory(click.x(), click.y())) {
            List<ChatHudLine.Visible> lines = ((ChatHudAccessor) MinecraftClient.getInstance().inGameHud.getChatHud())
                    .chattextselect$getVisibleMessages();
            int idx = chatSelect$screenYToVisibleIndex(click.y());
            if (idx >= 0 && idx < lines.size()) {
                chatSelect$startIdx = idx;
                chatSelect$endIdx = idx;
                chatSelect$dragging = true;
                return;
            }
        }
        chatSelect$startIdx = -1;
        chatSelect$endIdx = -1;
        chatSelect$dragging = false;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (chatSelect$dragging && click.button() == 0) {
            List<ChatHudLine.Visible> lines = ((ChatHudAccessor) MinecraftClient.getInstance().inGameHud.getChatHud())
                    .chattextselect$getVisibleMessages();
            int idx = chatSelect$screenYToVisibleIndex(click.y());
            chatSelect$endIdx = MathHelper.clamp(idx, 0, lines.size() - 1);
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0) {
            chatSelect$dragging = false;
        }
        return super.mouseReleased(click);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void chatSelect$onKeyPressed(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        if (!input.isCopy()) return;
        if (chatSelect$startIdx < 0 || chatSelect$endIdx < 0) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ChatHudAccessor accessor = (ChatHudAccessor) mc.inGameHud.getChatHud();
        List<ChatHudLine.Visible> visibleLines = accessor.chattextselect$getVisibleMessages();
        if (visibleLines.isEmpty()) return;

        int lo = MathHelper.clamp(Math.min(chatSelect$startIdx, chatSelect$endIdx), 0, visibleLines.size() - 1);
        int hi = MathHelper.clamp(Math.max(chatSelect$startIdx, chatSelect$endIdx), 0, visibleLines.size() - 1);

        SequencedSet<Integer> seenTimes = new LinkedHashSet<>();
        for (int i = hi; i >= lo; i--) {
            seenTimes.add(visibleLines.get(i).addedTime());
        }

        List<ChatHudLine> messages = accessor.chattextselect$getMessages();
        List<String> parts = new ArrayList<>();
        for (int time : seenTimes) {
            for (ChatHudLine msg : messages) {
                if (msg.creationTick() == time) {
                    parts.add(msg.content().getString());
                    break;
                }
            }
        }

        mc.keyboard.setClipboard(String.join("\n", parts));
        cir.setReturnValue(true);
        cir.cancel();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void chatSelect$onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (chatSelect$startIdx < 0 || chatSelect$endIdx < 0) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        ChatHud chat = mc.inGameHud.getChatHud();
        ChatHudAccessor accessor = (ChatHudAccessor) chat;
        List<ChatHudLine.Visible> lines = accessor.chattextselect$getVisibleMessages();
        if (lines.isEmpty()) return;

        int scroll = accessor.chattextselect$getScrolledLines();
        float scale = ((Double) mc.options.getChatScale().getValue()).floatValue();
        int entryHeight = (int) (9.0 * ((Double) mc.options.getChatLineSpacing().getValue() + 1.0));
        int screenHeight = mc.getWindow().getScaledHeight();
        int chatBottomLocal = MathHelper.floor((screenHeight - 40) / scale);
        int linesPerPage = chat.getVisibleLineCount();
        int maxWidth = MathHelper.ceil(ChatHud.getWidth((Double) mc.options.getChatWidth().getValue()) / scale);

        int lo = Math.min(chatSelect$startIdx, chatSelect$endIdx);
        int hi = Math.max(chatSelect$startIdx, chatSelect$endIdx);
        int highlightColor = (100 << 24) | (120 << 8) | 215;

        context.getMatrices().pushMatrix();
        context.getMatrices().scale(scale, scale);
        context.getMatrices().translate(4.0f, 0.0f);

        for (int tmIdx = lo; tmIdx <= hi; tmIdx++) {
            if (tmIdx < 0 || tmIdx >= lines.size()) continue;
            int displayIdx = tmIdx - scroll;
            if (displayIdx < 0 || displayIdx >= linesPerPage) continue;
            int entryBottom = chatBottomLocal - displayIdx * entryHeight;
            int entryTop = entryBottom - entryHeight;
            context.fill(-4, entryTop, maxWidth + 8, entryBottom, highlightColor);
        }

        context.getMatrices().popMatrix();
    }
}
