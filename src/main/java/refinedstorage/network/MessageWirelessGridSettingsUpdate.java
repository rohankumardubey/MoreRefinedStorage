package refinedstorage.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import refinedstorage.RefinedStorageItems;
import refinedstorage.tile.grid.TileGrid;

public class MessageWirelessGridSettingsUpdate extends MessageHandlerPlayerToServer<MessageWirelessGridSettingsUpdate> implements IMessage {
    private int hand;
    private int viewType;
    private int sortingDirection;
    private int sortingType;
    private int searchBoxMode;

    public MessageWirelessGridSettingsUpdate() {
    }

    public MessageWirelessGridSettingsUpdate(int hand, int viewType, int sortingDirection, int sortingType, int searchBoxMode) {
        this.hand = hand;
        this.viewType = viewType;
        this.sortingDirection = sortingDirection;
        this.sortingType = sortingType;
        this.searchBoxMode = searchBoxMode;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hand = buf.readInt();
        viewType = buf.readInt();
        sortingDirection = buf.readInt();
        sortingType = buf.readInt();
        searchBoxMode = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(hand);
        buf.writeInt(viewType);
        buf.writeInt(sortingDirection);
        buf.writeInt(sortingType);
        buf.writeInt(searchBoxMode);
    }

    @Override
    public void handle(MessageWirelessGridSettingsUpdate message, EntityPlayerMP player) {
        ItemStack held = player.getHeldItem((message.hand < 0 || message.hand > EnumHand.values().length - 1) ? EnumHand.MAIN_HAND : EnumHand.values()[message.hand]);

        if (held != null && held.getItem() == RefinedStorageItems.WIRELESS_GRID && held.getTagCompound() != null) {
            if (TileGrid.isValidViewType(message.viewType)) {
                held.getTagCompound().setInteger(TileGrid.NBT_VIEW_TYPE, message.viewType);
            }

            if (TileGrid.isValidSortingDirection(message.sortingDirection)) {
                held.getTagCompound().setInteger(TileGrid.NBT_SORTING_DIRECTION, message.sortingDirection);
            }

            if (TileGrid.isValidSortingType(message.sortingType)) {
                held.getTagCompound().setInteger(TileGrid.NBT_SORTING_TYPE, message.sortingType);
            }

            if (TileGrid.isValidSearchBoxMode(message.searchBoxMode)) {
                held.getTagCompound().setInteger(TileGrid.NBT_SEARCH_BOX_MODE, message.searchBoxMode);
            }
        }
    }
}
