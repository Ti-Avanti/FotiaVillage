package gg.fotia.fotiavillage.compat;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

/** 仅用于缺少价格 API 的早期 Paper；通过公开序列化接口保留完整配方。 */
@SuppressWarnings("deprecation")
public final class LegacyMerchantRecipes {
    private LegacyMerchantRecipes() {
    }

    public static String snapshot(Villager villager, List<ItemStack> results) {
        return Base64.getEncoder().encodeToString(withResults(villager, results));
    }

    public static List<MerchantRecipe> restore(String snapshot, World world) {
        return recipes(Base64.getDecoder().decode(snapshot), world);
    }

    public static List<MerchantRecipe> copyWithResults(Villager villager, List<ItemStack> results) {
        return recipes(withResults(villager, results), villager.getWorld());
    }

    private static List<MerchantRecipe> recipes(byte[] data, World world) {
        // 此接口只构造未加入世界的实体，既不生成测试村民，也不覆盖现有 UUID。
        Entity decoded = Bukkit.getUnsafe().deserializeEntity(data, world, false);
        if (!(decoded instanceof Villager villager)) {
            throw new IllegalArgumentException("Legacy trade snapshot is not a villager");
        }
        return List.copyOf(villager.getRecipes());
    }

    private static byte[] withResults(Villager villager, List<ItemStack> results) {
        try {
            CompoundBinaryTag entity = read(Bukkit.getUnsafe().serializeEntity(villager));
            CompoundBinaryTag offers = entity.getCompound("Offers");
            ListBinaryTag recipes = offers.getList("Recipes", BinaryTagTypes.COMPOUND);
            if (recipes.size() != results.size()) {
                throw new IllegalArgumentException("Legacy trade result count does not match recipes");
            }
            ListBinaryTag.Builder<CompoundBinaryTag> updated = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            for (int i = 0; i < results.size(); i++) {
                CompoundBinaryTag result = read(Bukkit.getUnsafe().serializeItem(results.get(i))).remove("DataVersion");
                updated.add(recipes.getCompound(i).put("sell", result));
            }
            // 不保留原实体的 PDC/历史快照，避免反复治愈后嵌套膨胀。
            return write(CompoundBinaryTag.builder()
                .putString("id", entity.getString("id"))
                .putInt("DataVersion", entity.getInt("DataVersion"))
                .put("VillagerData", entity.getCompound("VillagerData"))
                .put("Offers", offers.put("Recipes", updated.build()))
                .build());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to preserve legacy merchant prices", ex);
        }
    }

    private static CompoundBinaryTag read(byte[] data) throws IOException {
        return BinaryTagIO.reader().read(new ByteArrayInputStream(data), BinaryTagIO.Compression.GZIP);
    }

    private static byte[] write(CompoundBinaryTag tag) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(tag, output, BinaryTagIO.Compression.GZIP);
        return output.toByteArray();
    }
}
