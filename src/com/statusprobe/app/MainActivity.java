package com.statusprobe.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int INK=0xFF172C25, MUTED=0xFF60716A, GREEN=0xFF17624D, PAPER=0xFFF7F8F5;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final ExecutorService previews = Executors.newSingleThreadExecutor();
    ProgressBar loading;
    LinearLayout selectionBar;
    RadioGroup filters;
    GalleryViewer activeViewer;
    final Map<String,FrameLayout> tiles=new HashMap<>();
    LinearLayout list;
    TextView summary;
    Button scanButton, baselineButton, folderButton;
    android.content.SharedPreferences prefs;
    ArrayList<Item> items = new ArrayList<>();
    Uri tree;
    Item pendingSave;
    boolean scanned;
    Button assignButton;
    Button clearSelection;
    String filter="All";
    final Map<String,TextView> selectionBadges=new HashMap<>();
    final Set<String> selected = new HashSet<>();
    volatile int renderVersion;
    final android.util.LruCache<String, Bitmap> thumbnails = new android.util.LruCache<String, Bitmap>(8*1024*1024) {
        protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    static class Item {
        Uri uri; String name, mime, key; long size, modified;
        Item(Uri u, String n, String m, long s, long time) { uri=u; name=n; mime=m; size=s; modified=time; key=u.toString()+":"+s+":"+time; }
    }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("probe", MODE_PRIVATE);
        if(state!=null){filter=state.getString("filter","All");ArrayList<String> restored=state.getStringArrayList("selection");if(restored!=null)selected.addAll(restored);}
        String saved=prefs.getString("tree", null); if(saved!=null) tree=Uri.parse(saved);
        if(state!=null && state.containsKey("pendingUri")) pendingSave=new Item(Uri.parse(state.getString("pendingUri")),state.getString("pendingName"),state.getString("pendingMime"),0,0);
        LinearLayout body=new LinearLayout(this); body.setOrientation(1); body.setPadding(dp(16),dp(12),dp(16),0); body.setBackgroundColor(PAPER);
        LinearLayout toolbar=new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL); body.addView(toolbar);
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(1);toolbar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=label("Status Gallery",25);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));brand.addView(title);
        TextView subtitle=label("Your moments, kept close.",12);subtitle.setTextColor(MUTED);subtitle.setPadding(0,0,0,dp(12));brand.addView(subtitle);
        scanButton=button(toolbar,"↻",v->scan());scanButton.setTextSize(25);scanButton.setContentDescription("Refresh gallery");scanButton.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));
        folderButton=button(toolbar,"More",v->{PopupMenu menu=new PopupMenu(this,v); menu.getMenu().add("Choose folder").setOnMenuItemClickListener(m->{choose();return true;}); menu.getMenu().add("Set baseline").setEnabled(scanned).setOnMenuItemClickListener(m->{baselineButton.performClick();return true;}); menu.getMenu().add("Help").setOnMenuItemClickListener(m->{new AlertDialog.Builder(this).setTitle("Your status gallery").setMessage("Tap a thumbnail to view. Swipe photos or use Previous and Next to browse. Long-press to select several statuses, then assign a person. Names are manual; new files start Unassigned.\n\nTo test unseen media, set a baseline, ask a contact to post, and refresh before opening their status. NEW means changed since baseline, not proof of being unseen.").setPositiveButton("OK",null).show();return true;});menu.show();});
        folderButton.setText("⋮");folderButton.setTextSize(24);folderButton.setContentDescription("More options");folderButton.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));
        baselineButton=button(body,"Set baseline from this scan",v->{
            Set<String> keys=new HashSet<>(); for(Item i:items) keys.add(i.key);
            prefs.edit().putStringSet("baseline",keys).putLong("baselineTime",System.currentTimeMillis()).apply(); render();
        }); baselineButton.setEnabled(false);
        baselineButton.setVisibility(View.GONE);
        filters=new RadioGroup(this); filters.setOrientation(LinearLayout.HORIZONTAL);filters.setPadding(dp(4),dp(4),dp(4),dp(4));filters.setBackground(round(0xFFE7EDE6,16));
        for(String name:new String[]{"All","Photos","Videos"}) {RadioButton tab=new RadioButton(this);tab.setId(View.generateViewId());tab.setText(name);tab.setTextSize(14);tab.setButtonDrawable((android.graphics.drawable.Drawable)null);tab.setGravity(Gravity.CENTER);tab.setMinHeight(dp(48));tab.setTypeface(Typeface.create("sans-serif-medium",0));filters.addView(tab,new RadioGroup.LayoutParams(0,dp(48),1));if(name.equals(filter))tab.setChecked(true);}
        filters.setOnCheckedChangeListener((g,id)->{filter=((RadioButton)g.findViewById(id)).getText().toString();styleFilters();render();});body.addView(filters);styleFilters();
        selectionBar=new LinearLayout(this);selectionBar.setGravity(Gravity.CENTER_VERTICAL);body.addView(selectionBar);
        assignButton=button(selectionBar,"Assign person",v->{ArrayList<Item> chosen=new ArrayList<>(); for(Item i:items) if(selected.contains(i.key)) chosen.add(i); assign(chosen);});
        assignButton.setLayoutParams(new LinearLayout.LayoutParams(0,dp(52),1));primary(assignButton);
        assignButton.setEnabled(false);
        clearSelection=button(selectionBar,"Cancel",v->{selected.clear();updateSelection();});updateSelection();
        summary=label("",12);summary.setTextColor(MUTED); body.addView(summary);
        loading=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);loading.setIndeterminate(true);loading.setVisibility(View.INVISIBLE);body.addView(loading,new LinearLayout.LayoutParams(-1,dp(3)));
        ScrollView scroll=new ScrollView(this); body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        list=new LinearLayout(this); list.setOrientation(1);list.setPadding(0,0,0,dp(24));scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false); scroll.addView(list); setContentView(body);
        if(tree!=null) scan();else render();
    }
    android.graphics.drawable.GradientDrawable round(int color,int radius){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    android.graphics.drawable.Drawable ripple(int color,int radius){return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2217624D),round(color,radius),null);}
    void primary(Button b){b.setTextColor(Color.WHITE);b.setBackground(ripple(GREEN,14));}
    void styleFilters(){for(int j=0;j<filters.getChildCount();j++){RadioButton t=(RadioButton)filters.getChildAt(j);t.setTextColor(t.isChecked()?Color.WHITE:MUTED);t.setBackground(ripple(t.isChecked()?GREEN:Color.TRANSPARENT,12));}}
    TextView label(String s,int size) { TextView t=new TextView(this); t.setText(s); t.setTextSize(size);t.setTextColor(INK);t.setFontFeatureSettings("kern"); t.setPadding(0,dp(6),0,dp(8)); return t; }
    Button button(LinearLayout parent,String text,View.OnClickListener listener) { Button b=new Button(this); b.setText(text); b.setAllCaps(false);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setTextColor(GREEN);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),0,dp(12),0);b.setBackground(ripple(0xFFE7EDE6,14)); b.setOnClickListener(listener);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(dp(3),dp(4),dp(3),dp(4));parent.addView(b,p); return b; }
    void choose() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.putExtra("android.content.extra.SHOW_ADVANCED",true);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia"));
        startActivityForResult(i,1);
    }
    void scan() {
        if(tree==null) { choose(); return; }
        final Uri selected=tree;
        renderVersion++;
        loading.setVisibility(View.VISIBLE);
        scanButton.setEnabled(false); baselineButton.setEnabled(false); folderButton.setEnabled(false);
        summary.setText("Scanning the selected folder…");
        worker.execute(()->{
            try {
                ArrayList<Item> found=new ArrayList<>();
                String id=DocumentsContract.getTreeDocumentId(selected);
                boolean statusFolder=id.toLowerCase(Locale.ROOT).endsWith("/.statuses") || id.toLowerCase(Locale.ROOT).endsWith(":.statuses");
                boolean located=read(selected,id,statusFolder,0,found);
                found.sort((a,b)->{int order=Long.compare(b.modified,a.modified); return order!=0?order:a.name.compareToIgnoreCase(b.name);});
                runOnUiThread(()->{if(isDestroyed())return;loading.setVisibility(View.INVISIBLE);items=found; scanned=located; scanButton.setEnabled(true); folderButton.setEnabled(true); render(); if(!located) summary.setText("Status folder not found. Choose WhatsApp's Media folder.");});
            } catch(Exception e) { runOnUiThread(()->{if(isDestroyed())return;loading.setVisibility(View.INVISIBLE);scanned=false; items.clear();MainActivity.this.selected.clear(); scanButton.setEnabled(true); folderButton.setEnabled(true);render(); summary.setText("Folder access unavailable. Reconnect your folder to try again.");}); }
        });
    }
    boolean read(Uri selected,String id,boolean inStatus,int depth,ArrayList<Item> result) throws Exception {
        boolean located=inStatus;
        String[] cols={"document_id","_display_name","mime_type","_size","last_modified"};
        try(Cursor c=getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(selected,id),cols,null,null,null)) {
            if(c==null) throw new IOException("Storage provider returned no result");
            while(c.moveToNext()) {
                String child=c.getString(0), name=c.getString(1), mime=c.getString(2);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if(depth<4 && (name.equalsIgnoreCase(".Statuses") || name.equalsIgnoreCase("WhatsApp") || name.equalsIgnoreCase("WhatsApp Business") || name.equalsIgnoreCase("Media") || name.equalsIgnoreCase("com.whatsapp") || name.equalsIgnoreCase("com.whatsapp.w4b")))
                        located=read(selected,child,name.equalsIgnoreCase(".Statuses"),depth+1,result)||located;
                } else if(inStatus) {
                    String lower=name.toLowerCase(Locale.ROOT);
                    if(mime==null || !mime.startsWith("image/") && !mime.startsWith("video/")) {
                        if(lower.endsWith(".jpg")||lower.endsWith(".jpeg")) mime="image/jpeg";
                        else if(lower.endsWith(".png")) mime="image/png";
                        else if(lower.endsWith(".webp")) mime="image/webp";
                        else if(lower.endsWith(".mp4")) mime="video/mp4"; else continue;
                    }
                    result.add(new Item(DocumentsContract.buildDocumentUriUsingTree(selected,child),name,mime,c.getLong(3),c.getLong(4)));
                }
            }
        } return located;
    }
    void render() {
        final int version=++renderVersion;
        list.removeAllViews(); selectionBadges.clear();tiles.clear(); baselineButton.setEnabled(scanned);
        Set<String> current=new HashSet<>(); for(Item i:items) current.add(i.key); selected.retainAll(current); updateSelection();
        Set<String> baseline=prefs.getStringSet("baseline",new HashSet<>());
        boolean hasBaseline=prefs.contains("baselineTime"); int fresh=0;
        for(Item i:items) if(!baseline.contains(i.key)) fresh++;
        int visible=0;for(Item i:items)if(matchesFilter(i))visible++;
        summary.setText(visible+" "+(filter.equals("All")?"statuses":filter.toLowerCase(Locale.ROOT))+(hasBaseline?" · "+fresh+" new/changed overall":"")+"  ·  Hold to select");
        Map<String,ArrayList<Item>> groups=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(Item i:items) {if(!matchesFilter(i))continue;String person=person(i); if(!groups.containsKey(person)) groups.put(person,new ArrayList<>()); groups.get(person).add(i);}
        if(groups.isEmpty()){
            LinearLayout empty=new LinearLayout(this);empty.setOrientation(1);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(40),dp(24),dp(40));list.addView(empty);
            ImageView icon=new ImageView(this);icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));empty.addView(icon,new LinearLayout.LayoutParams(dp(80),dp(80)));
            TextView heading=label(tree==null?"A home for your statuses":"Nothing here yet",24);heading.setGravity(Gravity.CENTER);empty.addView(heading);
            TextView hint=label(tree==null?"Connect your WhatsApp Media folder to browse and keep the moments you love.":"Available photos and videos will appear here. Try refreshing or choose a different filter.",15);hint.setTextColor(MUTED);hint.setGravity(Gravity.CENTER);empty.addView(hint);
            primary(button(empty,tree==null||!scanned?"Connect folder":"Refresh gallery",v->{if(tree==null||!scanned)choose();else scan();}));
        }
        for(Map.Entry<String,ArrayList<Item>> group:groups.entrySet()) {
            String person=group.getKey(); ArrayList<Item> members=group.getValue();
            TextView heading=label((person.isEmpty()?"Unassigned":person)+" · "+members.size(),18);
            heading.setTypeface(Typeface.create("sans-serif-medium",0));heading.setPadding(dp(3),dp(20),0,dp(10)); list.addView(heading);
            int columns=getResources().getConfiguration().screenWidthDp>=600?5:3;
            LinearLayout row=null; int index=0;
            for(Item i:members) {
                if(index%columns==0){row=new LinearLayout(this);list.addView(row,new LinearLayout.LayoutParams(-1,-2));}
                FrameLayout tile=new FrameLayout(this){protected void onMeasure(int w,int h){int side=View.MeasureSpec.getSize(w);super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(side,View.MeasureSpec.EXACTLY));}};
                LinearLayout.LayoutParams tileParams=new LinearLayout.LayoutParams(0,-2,1);tileParams.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(tile,tileParams);tile.setBackground(round(0xFFE7EDE6,12));tile.setClipToOutline(true);tiles.put(i.key,tile);
                ImageView thumb=new ImageView(this); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setBackgroundColor(Color.rgb(230,237,234));
                tile.setContentDescription((i.mime.startsWith("video/")?"Video":"Photo")+" from "+(person.isEmpty()?"Unassigned":person));
                tile.addView(thumb,new FrameLayout.LayoutParams(-1,-1));loadThumbnail(i,thumb,version);
                if(i.mime.startsWith("video/"))badge(tile,"▶ Video",Gravity.BOTTOM|Gravity.LEFT);
                if(hasBaseline&&!baseline.contains(i.key))badge(tile,"NEW",Gravity.TOP|Gravity.LEFT);
                TextView selection=badge(tile,"✓",Gravity.TOP|Gravity.RIGHT);selectionBadges.put(i.key,selection);selection.setVisibility(selected.contains(i.key)?View.VISIBLE:View.GONE);
                tile.setForeground(ripple(Color.TRANSPARENT,12));tile.setOnClickListener(v->{if(selected.isEmpty())preview(i);else toggle(i);});tile.setOnLongClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);toggle(i);return true;});index++;
            }
            while(index%columns!=0){row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));index++;}
        }
        updateSelection();
    }
    boolean matchesFilter(Item i){return filter.equals("All") || (filter.equals("Videos")==i.mime.startsWith("video/"));}
    TextView badge(FrameLayout tile,String text,int gravity){TextView b=new TextView(this);b.setText(text);b.setTextSize(10);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setTextColor(Color.WHITE);b.setBackground(round(0xD0172C25,7));b.setPadding(dp(6),dp(4),dp(6),dp(4));FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,gravity);p.setMargins(dp(6),dp(6),dp(6),dp(6));tile.addView(b,p);return b;}
    void toggle(Item i){if(!selected.add(i.key))selected.remove(i.key);updateSelection();}
    int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
    String person(Item i) {return prefs.getString("person:"+i.key,"");}
    void updateSelection() {selectionBar.setVisibility(selected.isEmpty()?View.GONE:View.VISIBLE);assignButton.setEnabled(!selected.isEmpty());assignButton.setText("Assign person · "+selected.size());for(Map.Entry<String,TextView> e:selectionBadges.entrySet())e.getValue().setVisibility(selected.contains(e.getKey())?View.VISIBLE:View.GONE);for(Map.Entry<String,FrameLayout> e:tiles.entrySet()){boolean on=selected.contains(e.getKey());e.getValue().setSelected(on);android.graphics.drawable.GradientDrawable border=round(on?0x3317624D:Color.TRANSPARENT,12);if(on)border.setStroke(dp(3),0xFFC5EF83);e.getValue().setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x3317624D),border,null));}}
    void assign(List<Item> chosen) {
        if(chosen.isEmpty())return;
        AutoCompleteTextView input=new AutoCompleteTextView(this); input.setSingleLine(true); input.setHint("Person's name"); input.setThreshold(0);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        Set<String> known=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for(Map.Entry<String,?> e:prefs.getAll().entrySet()) if(e.getKey().startsWith("person:") && e.getValue() instanceof String && !((String)e.getValue()).isEmpty()) known.add((String)e.getValue());
        input.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_dropdown_item_1line,new ArrayList<>(known)));
        if(chosen.size()==1) input.setText(person(chosen.get(0)));
        LinearLayout content=new LinearLayout(this); content.setOrientation(1); content.setPadding(dp(24),dp(8),dp(24),0);
        content.addView(label("Label "+chosen.size()+" status(es). This does not identify future posts automatically.",14)); content.addView(input);
        if(!known.isEmpty()) button(content,"Choose existing person",v->{String[] names=known.toArray(new String[0]); new AlertDialog.Builder(this).setTitle("Choose person").setItems(names,(d,n)->input.setText(names[n])).show();});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Assign person").setView(content).setPositiveButton("Save",null).setNeutralButton("Unassign",(d,w)->savePerson(chosen,"")).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String name=input.getText().toString().trim(); if(name.isEmpty()){input.setError("Enter a name or use Unassign");return;} for(String existing:known)if(existing.equalsIgnoreCase(name)){name=existing;break;} savePerson(chosen,name);dialog.dismiss();})); dialog.show();
    }
    void savePerson(List<Item> chosen,String name) {
        android.content.SharedPreferences.Editor edit=prefs.edit();
        for(Item i:chosen){if(name.isEmpty())edit.remove("person:"+i.key);else edit.putString("person:"+i.key,name);selected.remove(i.key);}
        edit.apply(); render(); message(name.isEmpty()?"Moved to Unassigned":"Grouped under "+name);
    }
    void loadThumbnail(Item item,ImageView view,int version) {
        Bitmap cached=thumbnails.get(item.key); if(cached!=null){view.setImageBitmap(cached);return;}
        worker.execute(()->{if(version!=renderVersion)return; try {
            Bitmap bitmap;
            if(item.mime.startsWith("video/")) {
                android.media.MediaMetadataRetriever retriever=new android.media.MediaMetadataRetriever();
                try {retriever.setDataSource(this,item.uri);bitmap=retriever.getScaledFrameAtTime(0,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,480,270);} finally {retriever.release();}
            } else {
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                try(InputStream in=getContentResolver().openInputStream(item.uri)){BitmapFactory.decodeStream(in,null,options);}
                options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>640)options.inSampleSize*=2;
                options.inJustDecodeBounds=false;try(InputStream in=getContentResolver().openInputStream(item.uri)){bitmap=BitmapFactory.decodeStream(in,null,options);}
            }
            if(bitmap!=null){thumbnails.put(item.key,bitmap);runOnUiThread(()->{if(!isDestroyed()&&version==renderVersion)view.setImageBitmap(bitmap);});}
        } catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&version==renderVersion)view.setContentDescription("Thumbnail unavailable; tap to preview");});}});
    }
    void saveCopy(Item item) {
        pendingSave=item; Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.setType(item.mime);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_TITLE,item.name);startActivityForResult(intent,2);
    }
    void preview(Item item) {
        ArrayList<Item> gallery=new ArrayList<>();for(Item i:items)if(matchesFilter(i))gallery.add(i);
        gallery.sort((a,b)->{int p=person(a).compareToIgnoreCase(person(b));return p!=0?p:Long.compare(b.modified,a.modified);});
        activeViewer=new GalleryViewer(gallery,gallery.indexOf(item));activeViewer.show();
    }
    class GalleryViewer extends Dialog {
        final ArrayList<Item> gallery;int index,request=0;boolean closed;
        FrameLayout stage;TextView caption;Button previous,next;VideoView video;
        GalleryViewer(ArrayList<Item> gallery,int index){super(MainActivity.this,android.R.style.Theme_Material_NoActionBar);this.gallery=gallery;this.index=Math.max(0,index);}
        protected void onCreate(Bundle state){
            super.onCreate(state);LinearLayout root=new LinearLayout(MainActivity.this);root.setOrientation(1);root.setPadding(dp(12),dp(8),dp(12),dp(12));root.setBackgroundColor(0xFF101713);
            LinearLayout top=new LinearLayout(MainActivity.this);top.setGravity(Gravity.CENTER_VERTICAL);root.addView(top);
            Button close=button(top,"‹",v->dismiss());close.setTextSize(28);close.setContentDescription("Close viewer");close.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));caption=label("",16);caption.setPadding(dp(12),dp(8),0,dp(8));caption.setTextColor(Color.WHITE);top.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
            stage=new FrameLayout(MainActivity.this);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout navigation=new LinearLayout(MainActivity.this);root.addView(navigation);
            previous=button(navigation,"Previous",v->move(-1));next=button(navigation,"Next",v->move(1));
            LinearLayout actions=new LinearLayout(MainActivity.this);root.addView(actions);
            Button save=button(actions,"Save copy",v->{if(video!=null)video.pause();saveCopy(gallery.get(index));});button(actions,"Assign person",v->{Item chosen=gallery.get(index);dismiss();assign(Collections.singletonList(chosen));});
            for(LinearLayout bar:new LinearLayout[]{top,navigation,actions})for(int j=0;j<bar.getChildCount();j++)if(bar.getChildAt(j) instanceof Button){Button b=(Button)bar.getChildAt(j);b.setTextColor(0xFFE8F2E8);b.setBackground(ripple(0xFF25372D,14));if(bar!=top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1);p.setMargins(dp(4),dp(4),dp(4),dp(4));b.setLayoutParams(p);}}
            save.setTextColor(INK);save.setBackground(ripple(0xFFC5EF83,14));
            setContentView(root);getWindow().setLayout(-1,-1);getWindow().setStatusBarColor(0xFF101713);getWindow().setNavigationBarColor(0xFF101713);getWindow().getDecorView().setSystemUiVisibility(0);setOnDismissListener(d->{closed=true;request++;if(video!=null)video.stopPlayback();if(activeViewer==this)activeViewer=null;});display();
        }
        void move(int delta){int target=index+delta;if(target>=0&&target<gallery.size()){index=target;display();}}
        void display(){
            int token=++request;if(video!=null){video.stopPlayback();video=null;}stage.removeAllViews();Item item=gallery.get(index);
            caption.setText((person(item).isEmpty()?"Unassigned":person(item))+"\n"+(index+1)+" / "+gallery.size());previous.setEnabled(index>0);next.setEnabled(index+1<gallery.size());
            previous.setAlpha(previous.isEnabled()?1f:0.35f);next.setAlpha(next.isEnabled()?1f:0.35f);
            if(item.mime.startsWith("video/")){
                video=new VideoView(MainActivity.this);stage.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));MediaController controls=new MediaController(MainActivity.this);controls.setAnchorView(video);video.setMediaController(controls);
                video.setOnErrorListener((p,w,e)->{message("Could not play this video");return true;});video.setOnPreparedListener(p->{if(!closed&&token==request)video.start();});video.setVideoURI(item.uri);
            }else{
                ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);stage.addView(image,new FrameLayout.LayoutParams(-1,-1));
                ProgressBar progress=new ProgressBar(MainActivity.this);stage.addView(progress,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));
                image.setOnTouchListener(new View.OnTouchListener(){float x,y;public boolean onTouch(View v,android.view.MotionEvent e){if(e.getAction()==0){x=e.getX();y=e.getY();return true;}if(e.getAction()==1){float delta=e.getX()-x;if(Math.abs(delta)>dp(60)&&Math.abs(delta)>Math.abs(e.getY()-y))move(delta<0?1:-1);else v.performClick();return true;}return true;}});
                previews.execute(()->{if(closed||token!=request)return;try{
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                    try(InputStream in=getContentResolver().openInputStream(item.uri)){BitmapFactory.decodeStream(in,null,options);}options.inSampleSize=1;
                    while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1800)options.inSampleSize*=2;options.inJustDecodeBounds=false;
                    final Bitmap bitmap;try(InputStream in=getContentResolver().openInputStream(item.uri)){bitmap=BitmapFactory.decodeStream(in,null,options);}if(bitmap==null)throw new IOException("Unsupported image");
                    runOnUiThread(()->{if(!closed&&!isDestroyed()&&token==request){stage.removeView(progress);image.setImageBitmap(bitmap);}});
                }catch(Exception e){runOnUiThread(()->{if(!closed&&token==request){stage.removeView(progress);message("Preview unavailable");}});}});
            }
        }
    }
    void message(String s) {Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    protected void onSaveInstanceState(Bundle b) {super.onSaveInstanceState(b);b.putString("filter",filter);b.putStringArrayList("selection",new ArrayList<>(selected)); if(pendingSave!=null){b.putString("pendingUri",pendingSave.uri.toString()); b.putString("pendingName",pendingSave.name); b.putString("pendingMime",pendingSave.mime);}}
    protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==1) {
            Uri chosen=data.getData();
            try {getContentResolver().takePersistableUriPermission(chosen,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if(!chosen.equals(tree)) prefs.edit().remove("baseline").remove("baselineTime").apply();
                tree=chosen; prefs.edit().putString("tree",tree.toString()).apply(); scan();
            }catch(Exception e){message("Folder permission failed: "+e.getMessage());}
        } else if(request==2 && pendingSave!=null) {
            Item source=pendingSave; pendingSave=null; Uri destination=data.getData();
            worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(source.uri); OutputStream out=getContentResolver().openOutputStream(destination,"w")) {
                if(in==null||out==null)throw new IOException("Cannot open file"); byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))!=-1)out.write(buffer,0,n); out.flush();
                runOnUiThread(()->message("Copy saved."));
            }catch(Exception e){runOnUiThread(()->message("Save failed: "+e.getMessage()));}});
        }
    }
    public void onBackPressed(){if(!selected.isEmpty()){selected.clear();updateSelection();}else super.onBackPressed();}
    protected void onPause(){if(activeViewer!=null&&activeViewer.video!=null)activeViewer.video.pause();super.onPause();}
    protected void onDestroy(){renderVersion++;if(activeViewer!=null)activeViewer.dismiss();worker.shutdown();previews.shutdown();super.onDestroy();}
}
