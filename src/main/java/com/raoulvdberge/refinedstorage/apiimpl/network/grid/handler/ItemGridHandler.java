package com.raoulvdberge.refinedstorage.apiimpl.network.grid.handler;

import com.raoulvdberge.refinedstorage.RS;
import com.raoulvdberge.refinedstorage.RSTriggers;
import com.raoulvdberge.refinedstorage.api.autocrafting.engine.ICraftingTaskError;
import com.raoulvdberge.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.raoulvdberge.refinedstorage.api.network.INetwork;
import com.raoulvdberge.refinedstorage.api.network.grid.handler.IItemGridHandler;
import com.raoulvdberge.refinedstorage.api.network.security.Permission;
import com.raoulvdberge.refinedstorage.api.util.Action;
import com.raoulvdberge.refinedstorage.api.util.IComparer;
import com.raoulvdberge.refinedstorage.api.util.StackListEntry;
import com.raoulvdberge.refinedstorage.api.util.StackListResult;
import com.raoulvdberge.refinedstorage.apiimpl.API;
import com.raoulvdberge.refinedstorage.apiimpl.autocrafting.preview.CraftingPreviewElementError;
import com.raoulvdberge.refinedstorage.network.MessageGridCraftingPreviewResponse;
import com.raoulvdberge.refinedstorage.network.MessageGridCraftingStartResponse;
import com.raoulvdberge.refinedstorage.util.StackUtils;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ItemGridHandler implements IItemGridHandler {

    private final INetwork network;

    public ItemGridHandler(INetwork network) {
        this.network = network;
    }

    @Override
    public void onExtract(EntityPlayerMP player, ItemStack stack, int preferredSlot, int flags) {
        StackListEntry<ItemStack> entry =
                network.getItemStorageCache().getList()
                        .getEntry(stack, IComparer.COMPARE_NBT | IComparer.COMPARE_DAMAGE);
        if (entry != null)
            onExtract(player, entry.getId(), preferredSlot, flags);
    }

    @Override
    public void onExtract(EntityPlayerMP player, UUID id, int preferredSlot, int flags) {
        StackListEntry<ItemStack> entry = network.getItemStorageCache().getList().get(id);

        if (entry == null || !network.getSecurityManager().hasPermission(Permission.EXTRACT, player)) {
            return;
        }

        long itemSize = entry.getCount();
        // We copy here because some mods change the NBT tag of an item after getting the stack limit
        int maxItemSize = entry.getStack().getItem().getItemStackLimit(entry.getStack().copy());

        boolean single = (flags & EXTRACT_SINGLE) == EXTRACT_SINGLE;

        ItemStack held = player.inventory.getItemStack();

        if (single) {
            if (!held.isEmpty() && (!API.instance().getComparer().isEqualNoQuantity(entry.getStack(), held) ||
                                    held.getCount() + 1 > held.getMaxStackSize())) {
                return;
            }
        } else if (!player.inventory.getItemStack().isEmpty()) {
            return;
        }

        long size = 64;

        if ((flags & EXTRACT_HALF) == EXTRACT_HALF && itemSize > 1) {
            size = itemSize / 2;

            // Rationale for this check:
            // If we have 32 buckets, and we want to extract half, we expect/need to get 8 (max stack size 16 / 2).
            // Without this check, we would get 16 (total stack size 32 / 2).
            // Max item size also can't be 1. Otherwise, if we want to extract half of 8 lava buckets, we would get size 0 (1 / 2).
            if (size > maxItemSize / 2 && maxItemSize != 1) {
                size = maxItemSize / 2;
            }
        } else if (single) {
            size = 1;
        }

        size = Math.min(size, maxItemSize);

        // Do this before actually extracting, since external storage sends updates as soon as a change happens (so before the storage tracker used to track)
        network.getItemStorageTracker().changed(player, entry.getStack().copy());

        StackListResult<ItemStack> took = network.extractItem(entry.getStack(), size, Action.SIMULATE);

        if (took == null)
            return;

        if ((flags & EXTRACT_SHIFT) == EXTRACT_SHIFT) {
            IItemHandler playerInventory =
                    player.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP);
            if (playerInventory != null) {
                if (preferredSlot != -1) {
                    ItemStack remainder = playerInventory.insertItem(preferredSlot, took.getFixedStack(), true);
                    if (remainder.getCount() != took.getCount()) {
                        StackListResult<ItemStack> inserted = network.extractItem(entry.getStack(), size - remainder.getCount(), Action.PERFORM);
                        playerInventory.insertItem(preferredSlot, StackListResult.nullToEmpty(inserted), false);

                        took.setCount(remainder.getCount());
                    }
                }
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(playerInventory, took.getFixedStack(), true);
                took.setCount(took.getCount() - remainder.getCount());

                if (took.getCount() > 0) {
                    took = network.extractItem(entry.getStack(), took.getCount(), Action.PERFORM);

                    if (took != null && took.getCount() > 0)
                        ItemHandlerHelper.insertItemStacked(playerInventory, took.getFixedStack(), false);
                }
            }
        } else {
            took = network.extractItem(entry.getStack(), size, Action.PERFORM);

            if (took != null) {
                if (single && !held.isEmpty()) {
                    held.grow(1);
                } else {
                    player.inventory.setItemStack(took.getFixedStack());
                }

                player.updateHeldItem();
            }
        }

        network.getNetworkItemHandler().drainEnergy(player, RS.INSTANCE.config.wirelessGridExtractUsage);
    }

    @Override
    public ItemStack onInsert(EntityPlayerMP player, ItemStack stack, boolean single) {
        if (!network.getSecurityManager().hasPermission(Permission.INSERT, player)) {
            return stack;
        }

        grantAdvancement(player);

        network.getItemStorageTracker().changed(player, stack.copy());

        ItemStack remainder;
        if (single) {
            if (network.insertItem(stack, 1, Action.SIMULATE) == null) {
                network.insertItem(stack, 1, Action.PERFORM);
                stack.shrink(1);
            }
            remainder = stack;
        } else {
            remainder = network.insertItem(stack, stack.getCount(), Action.PERFORM);
        }

        network.getNetworkItemHandler().drainEnergy(player, RS.INSTANCE.config.wirelessGridInsertUsage);

        return remainder;
    }

    @Override
    public void onInsertHeldItem(EntityPlayerMP player, boolean single) {
        if (player.inventory.getItemStack().isEmpty() ||
            !network.getSecurityManager().hasPermission(Permission.INSERT, player)) {
            return;
        }

        grantAdvancement(player);

        ItemStack stack = player.inventory.getItemStack();
        int size = single ? 1 : stack.getCount();

        network.getItemStorageTracker().changed(player, stack.copy());

        if (single) {
            if (network.insertItem(stack, size, Action.SIMULATE) == null) {
                network.insertItem(stack, size, Action.PERFORM);

                stack.shrink(size);

                if (stack.getCount() == 0) {
                    player.inventory.setItemStack(ItemStack.EMPTY);
                }
            }
        } else {
            player.inventory.setItemStack(StackUtils.nullToEmpty(network.insertItem(stack, size, Action.PERFORM)));
        }

        player.updateHeldItem();

        network.getNetworkItemHandler().drainEnergy(player, RS.INSTANCE.config.wirelessGridInsertUsage);
    }

    private void grantAdvancement(EntityPlayerMP player) {
        if (network.getItemStorageCache().getList().getStored() > 100_000_000_000L) {
            RSTriggers.ONE_HUNDRED_BILLION_ITEMS_TRIGGER.trigger(player);
        }
    }

    @Override
    public ItemStack onShiftClick(EntityPlayerMP player, ItemStack stack) {
        return StackUtils.nullToEmpty(onInsert(player, stack, false));
    }

    @Override
    public void onCraftingPreviewRequested(EntityPlayerMP player, UUID id, int quantity, boolean noPreview) {
        if (!network.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player)) {
            return;
        }

        StackListEntry<ItemStack> stack = network.getItemStorageCache().getCraftablesList().get(id);

        if (stack != null) {
            ICraftingTask task = network.getCraftingManager().create(stack.getStack(), quantity);

            if (task == null) {
                return;
            }

            CompletableFuture.supplyAsync(() -> {
                ICraftingTaskError error = task.calculate();

                FMLCommonHandler.instance().getMinecraftServerInstance().addScheduledTask(() -> {
                    if (error == null && !task.hasMissing())
                        network.getCraftingManager().add(task);
                });

                if (error != null) {
                    RS.INSTANCE.network.sendTo(new MessageGridCraftingPreviewResponse(
                                    Collections.singletonList(new CraftingPreviewElementError()),
                                    task.getId(),
                                    task.getCalculationTime(),
                                    quantity,
                                    false),
                            player);
                } else if (noPreview && !task.hasMissing()) {
                    task.setCanUpdate(true);

                    RS.INSTANCE.network.sendTo(new MessageGridCraftingStartResponse(), player);
                } else {
                    RS.INSTANCE.network
                            .sendTo(new MessageGridCraftingPreviewResponse(task.getPreviewStacks(), task.getId(),
                                    task.getCalculationTime(), quantity, false), player);
                }

                return null;
            }).exceptionally(t -> {
                t.printStackTrace();
                task.onCancelled();
                return null;
            });
        }
    }

    @Override
    public void onCraftingRequested(EntityPlayerMP player, UUID id, int quantity) {
        if (quantity <= 0 || !network.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player)) {
            return;
        }

        ICraftingTask task = network.getCraftingManager().getTask(id);
        if (task != null)
            task.setCanUpdate(true);
    }

    @Override
    public void onCraftingCancelRequested(EntityPlayerMP player, @Nullable UUID id) {
        if (!network.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player)) {
            return;
        }

        network.getCraftingManager().cancel(id);

        network.getNetworkItemHandler().drainEnergy(player,
                id == null ? RS.INSTANCE.config.wirelessCraftingMonitorCancelAllUsage :
                        RS.INSTANCE.config.wirelessCraftingMonitorCancelUsage);
    }
}
