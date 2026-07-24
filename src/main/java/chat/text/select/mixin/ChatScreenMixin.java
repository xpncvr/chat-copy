package chat.text.select.mixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Style;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
    protected ChatScreenMixin() { super(null); }
    @Shadow private ChatComponent.DisplayMode displayMode;
    @Unique private int chatSelect$startIdx = -1;
    @Unique private int chatSelect$endIdx = -1;
    @Unique private boolean chatSelect$dragging = false;
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
        if (event.button() != 0) return;
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
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (chatSelect$dragging && event.button() == 0) {
            List<GuiMessage.Line> lines = ((ChatComponentAccessor) Minecraft.getInstance().gui.hud.getChat())
                    .chattextselect$getTrimmedMessages();
            int idx = chatSelect$screenYToTrimmedIndex(event.y());
            chatSelect$endIdx = Mth.clamp(idx, 0, lines.size() - 1);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            chatSelect$dragging = false;
        }
        return super.mouseReleased(event);
    }
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void chatSelect$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() != 67 || !event.hasControlDown()) return;
        if (chatSelect$startIdx < 0 || chatSelect$endIdx < 0) return;
        Minecraft mc = Minecraft.getInstance();
        List<GuiMessage.Line> lines = ((ChatComponentAccessor) mc.gui.hud.getChat())
                .chattextselect$getTrimmedMessages();
        if (lines.isEmpty()) return;
        int lo = Mth.clamp(Math.min(chatSelect$startIdx, chatSelect$endIdx), 0, lines.size() - 1);
        int hi = Mth.clamp(Math.max(chatSelect$startIdx, chatSelect$endIdx), 0, lines.size() - 1);
        SequencedSet<GuiMessage> seen = new LinkedHashSet<>();
        for (int i = hi; i >= lo; i--) {
            seen.add(lines.get(i).parent());
        }
        List<String> parts = new ArrayList<>();
        for (GuiMessage msg : seen) {
            parts.add(msg.content().getString());
        }
        mc.keyboardHandler.setClipboard(String.join("\n", parts));
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
        int highlightColor = ARGB.color(100, 0, 120, 215);
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(4.0f, 0.0f);
        for (int tmIdx = lo; tmIdx <= hi; tmIdx++) {
            if (tmIdx < 0 || tmIdx >= lines.size()) continue;
            int displayIdx = tmIdx - scroll;
            if (displayIdx < 0 || displayIdx >= linesPerPage) continue;
            int entryBottom = chatBottomLocal - displayIdx * entryHeight;
            int entryTop = entryBottom - entryHeight;
            graphics.fill(-4, entryTop, maxWidth + 8, entryBottom, highlightColor);
        }
        graphics.pose().popMatrix();
    }
}
