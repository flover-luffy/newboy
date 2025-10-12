package net.luffy.model;

import java.util.ArrayList;
import java.util.List;

public class WeidianItem {
    public final long id;
    public final String name;
    public final String pic;
    public final double price;
    public final String itemImg;
    public final boolean highlighted;
    public List<WeidianItemSku> skus;

    public WeidianItem(long id, String name, String pic) {
        this.id = id;
        this.name = name;
        this.pic = pic != null ? pic : "";
        this.price = 0.0;
        this.itemImg = null;
        this.highlighted = false;
    }

    public WeidianItem(long id, String name, double price, String itemImg, boolean highlighted) {
        this.id = id;
        this.name = name;
        this.pic = itemImg != null ? itemImg : "";  // pic字段用于图片URL
        this.price = price;
        this.itemImg = itemImg;  // 保持itemImg字段用于兼容性
        this.highlighted = highlighted;
    }

    public WeidianItem addSkus(long id, String name, String pic) {
        if (this.skus == null)
            this.skus = new ArrayList<>();

        this.skus.add(new WeidianItemSku(id, name, pic));
        return this;
    }


    public class WeidianItemSku {
        public final long id;
        public final String title;
        public final String pic;

        public WeidianItemSku(long id, String title, String pic) {
            this.id = id;
            this.title = title;
            this.pic = pic;
        }
    }
}