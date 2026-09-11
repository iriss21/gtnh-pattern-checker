package com.patternchecker.item;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import net.minecraftforge.oredict.ShapedOreRecipe;

import cpw.mods.fml.common.registry.GameRegistry;

import appeng.api.AEApi;

import com.patternchecker.PatternCheckerMod;

/**
 * Crafting recipe: iron / quartz fiber / redstone around a compass, mirroring
 * the original mod's recipe.
 */
public final class ModRecipes {

    private ModRecipes() {
    }

    public static void register() {
        ItemStack quartzFiber = AEApi.instance().definitions().parts().quartzFiber().maybeStack(1).orNull();
        if (quartzFiber == null) {
            return;
        }
        ItemStack result = new ItemStack(PatternCheckerMod.toolItem);
        GameRegistry.addRecipe(new ShapedOreRecipe(result,
                "IQI",
                "RCR",
                "IQI",
                'I', Items.iron_ingot,
                'Q', quartzFiber,
                'R', Items.redstone,
                'C', Items.compass));
    }
}
