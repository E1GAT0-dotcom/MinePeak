package gamemashup.minepeak;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * One of PEAK's items. Used (right click: PEAK's primary use) it does what PEAK's does, after PEAK's time to use it
 * (eating, drinking, bandaging: PeakActions), or its gear's own way (rope, pitons, the flare...: PeakGear). Used on a
 * friend (right click on them) it's theirs: PEAK's feeding a friend, which brings a passed-out one round.
 */
public class PeakItem extends Item {
	final String peak;

	public PeakItem(Properties properties, String peak) {
		super(properties);
		this.peak = peak;
	}

	PeakItemData.Def def() { return PeakItemData.get(peak); }

	@Override
	public Component getName(ItemStack stack) {
		var def = def();
		String name = def != null ? def.name() : PeakItems.ENGLISH.getOrDefault(peak, peak);
		int c = PeakItems.cooked(stack);
		return Component.literal(PeakItems.cookedWord(c) + name);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		PeakItems.prepare(stack);
		var def = def();
		if (def == null) return InteractionResult.PASS;
		if (PeakStatus.out(player)) return InteractionResult.FAIL;
		InteractionResult gear = PeakGear.use(level, player, hand, stack, def);
		if (gear != null) return gear;
		if (!PeakItems.castable(def)) return InteractionResult.PASS;
		if (stack.has(net.minecraft.core.component.DataComponents.CONSUMABLE)) return super.use(level, player, hand);
		// (no time to use it: at once)
		if (player instanceof ServerPlayer sp) {
			ItemStack left = PeakActions.cast(sp, sp, stack, def);
			if (left != stack) player.setItemInHand(hand, left);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
		var def = def();
		if (def == null || !(entity instanceof ServerPlayer sp)) return stack;
		if (PeakGear.handles(def)) return PeakGear.finish(sp, stack, def);
		return PeakActions.cast(sp, sp, stack, def);
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
		var def = def();
		if (def != null && entity instanceof ServerPlayer sp) return PeakGear.release(sp, stack, def, remaining);
		return false;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity user) {
		var def = def();
		if (def != null && PeakGear.handles(def)) return PeakGear.useTicks(stack, def);
		return super.getUseDuration(stack, user);
	}

	@Override
	public net.minecraft.world.item.ItemUseAnimation getUseAnimation(ItemStack stack) {
		var def = def();
		if (def != null && PeakGear.handles(def)) return PeakGear.animation(def);
		return super.getUseAnimation(stack);
	}

	@Override
	public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
		var def = def();
		if (def != null && !level.isClientSide() && PeakGear.handles(def)) PeakGear.useTick(entity, stack, def, remaining);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		var def = def();
		if (def == null || context.getPlayer() == null) return InteractionResult.PASS;
		PeakItems.prepare(context.getItemInHand());
		InteractionResult r = PeakGear.useOn(context, def);
		return r != null ? r : InteractionResult.PASS;
	}

	/** On a friend: PEAK's feeding (or healing) a friend, which wakes one that passed out. */
	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
		var def = def();
		if (def != null && def.has("RitualDaggerFeedBehavior") && target instanceof ServerPlayer tp && player instanceof ServerPlayer sp) {
			PeakGear.dagger(sp, tp, def);
			return InteractionResult.SUCCESS;
		}
		if (def == null || !(target instanceof Player friend) || !def.canUseOnFriend() || !PeakItems.castable(def)) return InteractionResult.PASS;
		if (player instanceof ServerPlayer sp && friend instanceof ServerPlayer tp && PeakWorld.scout(sp)) {
			boolean wasOut = PeakStatus.out(tp);
			PeakItems.prepare(stack);
			ItemStack left = PeakActions.cast(sp, tp, stack, def);
			player.setItemInHand(hand, left);
			if (wasOut && PeakStatus.sum(PeakStatus.of(tp)) <= 1f) PeakBadges.award(sp, "EmergencyPreparedness");
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
		if (owner instanceof ServerPlayer p) PeakGear.carried(p, stack, def(), slot == EquipmentSlot.MAINHAND);
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		var t = PeakItems.tag(stack);
		return t.contains("fuelMax") || t.contains("uses") && def() != null && def().totalUses() > 1 && t.getIntOr("uses", 0) < def().totalUses();
	}

	@Override
	public int getBarWidth(ItemStack stack) { return Math.round(13 * PeakGear.left(stack, def())); }

	@Override
	public int getBarColor(ItemStack stack) { return 0xFF7200; }
}
