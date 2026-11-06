package xyz.blossom.economy;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public final class BlossomEconomy extends JavaPlugin implements Listener, CommandExecutor {
    private Connection db;
    private String symbol;
    private final Map<UUID, String> sellConfirm = new HashMap<>();
    private final Map<UUID, Integer> ahPages = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) saveResource("messages.yml", false);
        reloadSettings();
        try {
            getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "economy.db").getAbsolutePath());
            try (Statement s = db.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, balance REAL NOT NULL)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS auctions (id INTEGER PRIMARY KEY AUTOINCREMENT, seller TEXT NOT NULL, seller_name TEXT NOT NULL, item TEXT NOT NULL, price REAL NOT NULL, expires INTEGER NOT NULL)");
            }
        } catch (Exception e) { getLogger().severe("Could not open SQLite database: " + e.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        for (String cmd : List.of("balance","pay","baltop","shop","sellgui","ah","be")) {
            Objects.requireNonNull(getCommand(cmd)).setExecutor(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::expireAuctions, 20L * 60, 20L * 60);
        getLogger().info("BlossomEconomy enabled.");
    }

    @Override public void onDisable() { try { if (db != null && !db.isClosed()) db.close(); } catch (SQLException ignored) {} }
    private void reloadSettings() { reloadConfig(); symbol = getConfig().getString("settings.currency-symbol", "$"); }
    private String msg(String key) {
        var c = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        return color(c.getString(key, "&cMissing message: " + key));
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void send(Player p, String key) { p.sendMessage(msg("prefix") + msg(key)); }
    private void sendRaw(Player p, String text) { p.sendMessage(msg("prefix") + color(text)); }
    private boolean admin(CommandSender s) { if (s.hasPermission("blossomeconomy.admin")) return true; s.sendMessage(msg("no-permission")); return false; }
    private double balance(UUID id) {
        try (PreparedStatement ps=db.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")) {
            ps.setString(1,id.toString()); try(ResultSet rs=ps.executeQuery()){ if(rs.next()) return rs.getDouble(1); }
        } catch(SQLException e){ getLogger().warning(e.getMessage()); } return 0;
    }
    private void ensure(Player p) {
        try(PreparedStatement ps=db.prepareStatement("INSERT OR IGNORE INTO accounts(uuid,name,balance) VALUES(?,?,?)")) {
            ps.setString(1,p.getUniqueId().toString()); ps.setString(2,p.getName()); ps.setDouble(3,getConfig().getDouble("settings.starting-balance",100)); ps.executeUpdate();
            try(PreparedStatement up=db.prepareStatement("UPDATE accounts SET name=? WHERE uuid=?")){up.setString(1,p.getName());up.setString(2,p.getUniqueId().toString());up.executeUpdate();}
        } catch(SQLException e){getLogger().warning(e.getMessage());}
    }
    private void setBalance(UUID id, String name, double amount) {
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO accounts(uuid,name,balance) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,balance=excluded.balance")){
            ps.setString(1,id.toString());ps.setString(2,name);ps.setDouble(3,amount);ps.executeUpdate();
        } catch(SQLException e){getLogger().warning(e.getMessage());}
    }
    private void add(UUID id, String name, double amount){setBalance(id,name,balance(id)+amount);}
    private String money(double n){return String.format(Locale.US,"%,.2f",n);}
    private boolean validAmount(String s){try{return Double.isFinite(Double.parseDouble(s)) && Double.parseDouble(s)>0;}catch(Exception e){return false;}}
    private ItemStack icon(Material m,String name,List<String> lore){
        ItemStack it=new ItemStack(m); ItemMeta im=it.getItemMeta(); im.setDisplayName(color(name)); im.setLore(lore.stream().map(this::color).toList()); it.setItemMeta(im); return it;
    }
    private Inventory gui(String title,int size,InventoryHolder holder){return Bukkit.createInventory(holder,size,color(title));}
    private record Menu(String kind,String data) implements InventoryHolder { public Inventory getInventory(){return null;} }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        String c=command.getName().toLowerCase(Locale.ROOT);
        if(c.equals("be")){if(!admin(sender))return true;if(a.length==1&&a[0].equalsIgnoreCase("reload")){reloadSettings();sender.sendMessage(msg("prefix")+msg("reload"));}else sender.sendMessage("/be reload");return true;}
        if(!(sender instanceof Player p)){sender.sendMessage("Players only.");return true;}
        ensure(p);
        switch(c){
            case "balance" -> sendRaw(p,"&7Balance: &a"+symbol+money(balance(p.getUniqueId())));
            case "pay" -> {
                if(!p.hasPermission("blossomeconomy.pay")){send(p,"no-permission");break;}
                if(a.length!=2){send(p,"pay-usage");break;}
                if(!validAmount(a[1])){send(p,"invalid-number");break;}
                Player target=Bukkit.getPlayerExact(a[0]); double amount=Double.parseDouble(a[1]);
                if(target==null){sendRaw(p,"&cThat player must be online.");break;}
                if(target.equals(p)){send(p,"cannot-pay-self");break;}
                if(amount>getConfig().getDouble("settings.max-pay",1e9)){send(p,"invalid-number");break;}
                if(balance(p.getUniqueId())<amount){send(p,"not-enough");break;}
                add(p.getUniqueId(),p.getName(),-amount);ensure(target);add(target.getUniqueId(),target.getName(),amount);
                sendRaw(p,"&aYou sent "+symbol+money(amount)+" to "+target.getName()+".");
                sendRaw(target,"&aYou received "+symbol+money(amount)+" from "+p.getName()+".");
            }
            case "baltop" -> showBaltop(p);
            case "shop" -> openShop(p);
            case "sellgui" -> openSell(p);
            case "ah" -> {
                if(a.length==2&&a[0].equalsIgnoreCase("sell"))listHeld(p,a[1]);
                else if(a.length==0)openAh(p,0);
                else send(p,"ah-usage");
            }
        }
        return true;
    }

    private void showBaltop(Player p){
        try(Statement s=db.createStatement();ResultSet rs=s.executeQuery("SELECT name,balance FROM accounts ORDER BY balance DESC LIMIT 10")){
            sendRaw(p,"&d&l--- Blossom Top Balances ---");int i=1;
            while(rs.next())sendRaw(p,"&e#"+(i++)+" &f"+rs.getString(1)+" &8- &a"+symbol+money(rs.getDouble(2)));
        }catch(SQLException e){p.sendMessage("Database error.");}
    }

    private void openShop(Player p){
        ConfigurationSection cats=getConfig().getConfigurationSection("shop.categories");
        if(cats==null){sendRaw(p,"&cNo shop categories configured.");return;}
        Inventory inv=gui("&d&lBlossom Shop",27,new Menu("shop",""));
        int slot=10;
        for(String key:cats.getKeys(false)){
            if(slot>16)break;
            String title=cats.getString(key+".title",key);
            inv.setItem(slot++,icon(Material.CHEST,title,List.of("&7Click to browse")));
        }
        p.openInventory(inv);
    }
    private void openCategory(Player p,String key){
        ConfigurationSection sec=getConfig().getConfigurationSection("shop.categories."+key+".items");
        if(sec==null||sec.getKeys(false).isEmpty()){send(p,"shop-empty");return;}
        Inventory inv=gui("&8Shop: "+key,54,new Menu("category",key)); int slot=0;
        for(String matName:sec.getKeys(false)){
            Material m=Material.matchMaterial(matName); if(m==null||!m.isItem())continue;
            double price=sec.getDouble(matName);
            inv.setItem(slot++,icon(m,"&f"+pretty(matName),List.of("&7Buy price: &a"+symbol+money(price),"&eClick to buy 1","&8Right-click to buy 64")));
            if(slot>=45)break;
        }
        inv.setItem(49,icon(Material.ARROW,"&cBack",List.of("&7Return to categories")));p.openInventory(inv);
    }
    private String pretty(String s){return Arrays.stream(s.toLowerCase(Locale.ROOT).split("_")).map(x->x.substring(0,1).toUpperCase()+x.substring(1)).collect(Collectors.joining(" "));}
    private void buyShop(Player p,String category,ItemStack clicked,boolean stack){
        Material m=clicked.getType();double unit=getConfig().getDouble("shop.categories."+category+".items."+m.name(),-1);
        int amount=stack?Math.min(64,m.getMaxStackSize()):1;double cost=unit*amount;
        if(unit<0)return;if(balance(p.getUniqueId())<cost){send(p,"not-enough");return;}
        HashMap<Integer,ItemStack> leftover=p.getInventory().addItem(new ItemStack(m,amount));
        if(!leftover.isEmpty()){sendRaw(p,"&cMake room in your inventory first.");return;}
        add(p.getUniqueId(),p.getName(),-cost);sendRaw(p,"&aPurchased "+amount+"x "+pretty(m.name())+" for "+symbol+money(cost)+".");
    }

    private void openSell(Player p){
        Inventory inv=gui("&8Sell Items",54,new Menu("sell",""));
        inv.setItem(49,icon(Material.EMERALD,"&aSell All",List.of("&7Sell all supported items","&eClick to continue")));
        inv.setItem(45,icon(Material.BARRIER,"&cCancel",List.of("&7Close without selling")));
        p.openInventory(inv);
    }
    private double sellValue(ItemStack it){
        if(it==null||it.getType().isAir())return 0;
        String path="shop.categories.";ConfigurationSection cats=getConfig().getConfigurationSection("shop.categories");
        if(cats==null)return 0;
        for(String cat:cats.getKeys(false)){double v=getConfig().getDouble(path+cat+".items."+it.getType().name(),-1);if(v>=0)return v*it.getAmount();}
        return 0;
    }
    private void completeSell(Player p,Inventory inv){
        double total=0;int count=0;
        for(int i=0;i<45;i++){ItemStack it=inv.getItem(i);double v=sellValue(it);if(v>0){total+=v;count+=it.getAmount();inv.setItem(i,null);}}
        if(total<=0){send(p,"sell-empty");return;}
        add(p.getUniqueId(),p.getName(),total);sendRaw(p,"&aSold "+count+" items for &e"+symbol+money(total)+"&a.");
        p.closeInventory();
    }

    private void listHeld(Player p,String priceText){
        if(!validAmount(priceText)){send(p,"invalid-number");return;}
        ItemStack held=p.getInventory().getItemInMainHand();
        if(held.getType().isAir()){sendRaw(p,"&cHold an item in your main hand.");return;}
        int max=getConfig().getInt("settings.auction.max-listings-per-player",10);
        try(PreparedStatement ps=db.prepareStatement("SELECT COUNT(*) FROM auctions WHERE seller=? AND expires>?")){
            ps.setString(1,p.getUniqueId().toString());ps.setLong(2,System.currentTimeMillis());try(ResultSet rs=ps.executeQuery()){if(rs.next()&&rs.getInt(1)>=max){send(p,"ah-full");return;}}
        }catch(SQLException e){return;}
        double fee=getConfig().getDouble("settings.auction.listing-fee",0);
        if(balance(p.getUniqueId())<fee){send(p,"not-enough");return;}
        double price=Double.parseDouble(priceText);
        String data=encode(held);
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO auctions(seller,seller_name,item,price,expires) VALUES(?,?,?,?,?)")){
            ps.setString(1,p.getUniqueId().toString());ps.setString(2,p.getName());ps.setString(3,data);ps.setDouble(4,price);
            ps.setLong(5,System.currentTimeMillis()+getConfig().getLong("settings.auction.listing-duration-hours",72)*3600000L);ps.executeUpdate();
            held.setAmount(0);add(p.getUniqueId(),p.getName(),-fee);sendRaw(p,"&aListed item for &e"+symbol+money(price)+"&a.");
        }catch(SQLException e){p.sendMessage("Could not create listing.");}
    }
    private String encode(ItemStack item){
        try(ByteArrayOutputStream b=new ByteArrayOutputStream();BukkitObjectOutputStream out=new BukkitObjectOutputStream(b)){out.writeObject(item);return Base64.getEncoder().encodeToString(b.toByteArray());}
        catch(IOException e){throw new IllegalStateException(e);}
    }
    private ItemStack decode(String data){
        try(ByteArrayInputStream b=new ByteArrayInputStream(Base64.getDecoder().decode(data));BukkitObjectInputStream in=new BukkitObjectInputStream(b)){return (ItemStack)in.readObject();}
        catch(Exception e){return null;}
    }
    private record Listing(int id,String seller,String sellerName,String item,double price){}
    private List<Listing> listings(){
        List<Listing> out=new ArrayList<>();
        try(PreparedStatement ps=db.prepareStatement("SELECT id,seller,seller_name,item,price FROM auctions WHERE expires>? ORDER BY id DESC")){ps.setLong(1,System.currentTimeMillis());try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(new Listing(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getDouble(5)));}}
        catch(SQLException e){getLogger().warning(e.getMessage());}return out;
    }
    private void openAh(Player p,int page){
        List<Listing> all=listings();int pages=Math.max(1,(all.size()+44)/45);page=Math.max(0,Math.min(page,pages-1));ahPages.put(p.getUniqueId(),page);
        Inventory inv=gui("&8Auction House &7("+ (page+1)+"/"+pages+")",54,new Menu("ah",String.valueOf(page)));
        int start=page*45;
        for(int i=start;i<Math.min(start+45,all.size());i++){
            Listing l=all.get(i);ItemStack it=decode(l.item());if(it==null)continue;ItemMeta meta=it.getItemMeta();
            List<String> lore=meta!=null&&meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();
            lore.add(color("&8&m----------------"));lore.add(color("&7Seller: &f"+l.sellerName()));lore.add(color("&7Price: &a"+symbol+money(l.price())));lore.add(color("&eClick to buy"));
            if(meta==null)meta=Bukkit.getItemFactory().getItemMeta(it.getType());meta.setLore(lore);it.setItemMeta(meta);inv.setItem(i-start,it);
        }
        inv.setItem(45,icon(Material.ARROW,"&ePrevious",List.of("&7Previous page")));
        inv.setItem(49,icon(Material.BOOK,"&fHow to sell",List.of("&7Hold an item and use:","&e/ah sell <price>")));
        inv.setItem(53,icon(Material.ARROW,"&eNext",List.of("&7Next page")));
        p.openInventory(inv);
    }
    private void buyListing(Player p,int slot,int page){
        List<Listing> all=listings();int index=page*45+slot;if(index<0||index>=all.size())return;Listing l=all.get(index);
        if(l.seller().equals(p.getUniqueId().toString())){send(p,"ah-own");return;}
        if(balance(p.getUniqueId())<l.price()){send(p,"not-enough");return;}
        ItemStack item=decode(l.item());if(item==null)return;
        if(p.getInventory().firstEmpty()<0){sendRaw(p,"&cMake room in your inventory first.");return;}
        // Conditional delete prevents two buyers purchasing the same listing.
        try(PreparedStatement del=db.prepareStatement("DELETE FROM auctions WHERE id=? AND expires>?")){
            del.setInt(1,l.id());del.setLong(2,System.currentTimeMillis());if(del.executeUpdate()!=1){sendRaw(p,"&cThis listing is no longer available.");openAh(p,page);return;}
        }catch(SQLException e){return;}
        double tax=getConfig().getDouble("settings.auction.tax-percent",0);double sellerGets=l.price()*(1-Math.max(0,Math.min(100,tax))/100);
        add(p.getUniqueId(),p.getName(),-l.price());OfflinePlayer seller=Bukkit.getOfflinePlayer(UUID.fromString(l.seller()));add(seller.getUniqueId(),l.sellerName(),sellerGets);
        p.getInventory().addItem(item);sendRaw(p,"&aPurchased item for &e"+symbol+money(l.price())+"&a.");
        Player online=Bukkit.getPlayer(seller.getUniqueId());if(online!=null)sendRaw(online,"&aYour auction sold for &e"+symbol+money(sellerGets)+"&a.");
        openAh(p,page);
    }
    private void expireAuctions(){
        try(PreparedStatement ps=db.prepareStatement("SELECT id,seller,seller_name,item FROM auctions WHERE expires<=?")){ps.setLong(1,System.currentTimeMillis());try(ResultSet rs=ps.executeQuery()){
            while(rs.next()){UUID id=UUID.fromString(rs.getString(2));ItemStack item=decode(rs.getString(4));Player online=Bukkit.getPlayer(id);
                if(online!=null&&item!=null){HashMap<Integer,ItemStack> rem=online.getInventory().addItem(item);for(ItemStack x:rem.values())online.getWorld().dropItemNaturally(online.getLocation(),x);send(online,"ah-expired");}
                else if(item!=null){getLogger().warning("Expired auction item for offline player "+rs.getString(3)+" retained in database is not auto-mailed.");}
            }
        }}
        catch(SQLException e){getLogger().warning(e.getMessage());return;}
        try(PreparedStatement del=db.prepareStatement("DELETE FROM auctions WHERE expires<=?")){del.setLong(1,System.currentTimeMillis());del.executeUpdate();}catch(SQLException ignored){}
    }

    @EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent e){ensure(e.getPlayer());}
    @EventHandler public void onClick(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return;
        Inventory top=e.getView().getTopInventory();if(!(top.getHolder() instanceof Menu menu))return;
        int raw=e.getRawSlot();
        if(menu.kind().equals("sell")) {
            if(e.isShiftClick() || raw >= 45) { e.setCancelled(true); return; }
            if(raw >= 0 && raw < 45) return; // Allow normal placement/removal in the sell slots.
        }
        if(raw>=0&&raw<top.getSize()){
            e.setCancelled(true);ItemStack clicked=e.getCurrentItem();if(clicked==null||clicked.getType().isAir())return;
            switch(menu.kind()){
                case "shop" -> {
                    List<String> keys=new ArrayList<>(Objects.requireNonNull(getConfig().getConfigurationSection("shop.categories")).getKeys(false));
                    int idx=raw-10;if(idx>=0&&idx<keys.size())openCategory(p,keys.get(idx));
                }
                case "category" -> {
                    if(raw==49){openShop(p);return;}
                    if(raw<45&&clicked.getType()!=Material.AIR)buyShop(p,menu.data(),clicked,e.isRightClick());
                }
                case "sell" -> {
                    if(raw==49)completeSell(p,top);
                    else if(raw==45)p.closeInventory();
                }
                case "ah" -> {
                    int page=Integer.parseInt(menu.data());
                    if(raw==45)openAh(p,page-1);else if(raw==53)openAh(p,page+1);else if(raw<45)buyListing(p,raw,page);
                }
            }
        } else if(menu.kind().equals("sell") && e.isShiftClick()) e.setCancelled(true);
    }
    @EventHandler public void onDrag(InventoryDragEvent e){
        if(e.getView().getTopInventory().getHolder() instanceof Menu menu) {
            if(!menu.kind().equals("sell")) e.setCancelled(true);
            else if(e.getRawSlots().stream().anyMatch(slot -> slot >= 45)) e.setCancelled(true);
        }
    }
    @EventHandler public void onClose(InventoryCloseEvent e){
        if(!(e.getPlayer() instanceof Player p))return;
        Inventory inv=e.getInventory();if(!(inv.getHolder() instanceof Menu menu)||!menu.kind().equals("sell"))return;
        // Return all items unless they were sold; GUI is a safe temporary container.
        for(int i=0;i<45;i++){ItemStack it=inv.getItem(i);if(it!=null&&it.getType()!=Material.AIR){HashMap<Integer,ItemStack> left=p.getInventory().addItem(it);for(ItemStack x:left.values())p.getWorld().dropItemNaturally(p.getLocation(),x);}}
    }
}
