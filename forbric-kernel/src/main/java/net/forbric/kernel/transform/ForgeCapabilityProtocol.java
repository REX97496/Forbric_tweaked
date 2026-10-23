/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.AncestorComposition;
import net.forbric.kernel.mixin.MixinInstructionFingerprint;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * The actual removed provider, composed state, and final emitted delegates must agree.
 *
 * <p>There are two shapes here, and which one applies is decided by the merged base's own manifest — the row's
 * {@code sourceSuperclass}, i.e. the class the merge actually dropped — never by a version string or a property:
 *
 * <ul>
 *   <li><b>NESTED</b> (26.2). The removed class is {@code CapabilityProvider$Entities/$BlockEntities/$Levels}: a
 *       subclass of the carrier that adds no field and implements the carrier's two {@code protected abstract} hooks.
 *       The per-root key <em>is</em> that class. {@link #nestedProvider}.</li>
 *   <li><b>SELF</b> (1.21.1). The removed class <em>is</em> {@code CapabilityProvider}. There is no subclass and no
 *       abstract hook — {@code gatherCapabilities()} is {@code protected final} and the carrier builds the
 *       {@code AttachCapabilitiesEvent} inside {@code doGatherCapabilities} from its own {@code baseClass} field.
 *       The per-root key is therefore the {@code Class} <em>argument</em>, and the three runtime delegates differ in
 *       nothing else. {@link #selfProvider}.</li>
 * </ul>
 *
 * <p>Both shapes end in the same place: the composed root carries Forge's own provider state, the delegates are the
 * kernel's own classes, and the emitted definition is fingerprinted.
 */
final class ForgeCapabilityProtocol implements Opcodes {
    private static final String BASE="net/minecraftforge/common/capabilities/CapabilityProvider";
    private static final String RUNTIME=ForgeCapabilityCompositionTransformer.RUNTIME;
    private static final String AS_FIELD=ForgeCapabilityCompositionTransformer.AS_FIELD;
    private static final String PROVIDER_IMPL_DESC="L"+ForgeCapabilityCompositionTransformer.PROVIDER_IMPL+";";
    private static final String ACCESSOR_SIGNATURE=ForgeCapabilityCompositionTransformer.ACCESSOR+ForgeCapabilityCompositionTransformer.ACCESSOR_DESC;
    private static final String HOLDER_LOOKUP="Lnet/minecraft/core/HolderLookup$Provider;";
    private static final String COMPOUND_TAG="Lnet/minecraft/nbt/CompoundTag;";
    private static final String DISPATCHER="Lnet/minecraftforge/common/capabilities/CapabilityDispatcher;";

    /** The three roots this protocol can prove, each with the delegate and accessor helper that belong to it. */
    private static final Map<String,String> KIND=Map.of(
            ForgeCapabilityCompositionTransformer.ENTITY,"Entities",
            ForgeCapabilityCompositionTransformer.BLOCK_ENTITY,"BlockEntities",
            ForgeCapabilityCompositionTransformer.LEVEL,"Levels");
    private static final Map<String,String> HELPER=Map.of(
            ForgeCapabilityCompositionTransformer.ENTITY,"entity",
            ForgeCapabilityCompositionTransformer.BLOCK_ENTITY,"blockEntity",
            ForgeCapabilityCompositionTransformer.LEVEL,"level");

    // ---- the SELF shape's pinned carrier. Every list below is a measured token stream, not a guess: they come from
    // `javap -p -c` on the real 1.21.1 forge-runtime carrier and are re-measured by the round that adds a new line.

    /** Declaration order and access included: this is the state the delegate inherits, and the whole of it. */
    private static final List<String> CARRIER_FIELDS=List.of(
            "8:SUPPORTS_LAZY_CAPABILITIES:Z",
            "18:baseClass:Ljava/lang/Class;",
            "2:capabilities:"+DISPATCHER,
            "2:valid:Z",
            "2:isLazy:Z",
            "2:lazyParentSupplier:Ljava/util/function/Supplier;",
            "2:lazyData:"+COMPOUND_TAG,
            "2:registryAccess:"+HOLDER_LOOKUP,
            "2:initialized:Z");
    private static final List<String> CARRIER_CTOR_CLASS=List.of("v25:0","v25:1","o3","m183:"+BASE+".<init>(Ljava/lang/Class;Z)V","o177");
    /**
     * {@code <init>(Class, boolean)}: the field initialisation order, and {@code isLazy = SUPPORTS_LAZY_CAPABILITIES
     * && eager} as {@code getstatic; ifeq; iload; ifeq; ...}. Pinned because {@code AsField(Class, B)} reaches this
     * constructor with {@code eager == false}, which is what makes an un-initialised provider empty <em>forever</em>.
     */
    private static final List<String> CARRIER_CTOR_CLASS_BOOLEAN=List.of(
            "v25:0","m183:java/lang/Object.<init>()V",
            "v25:0","o4","f181:"+BASE+".validZ",
            "v25:0","o3","f181:"+BASE+".isLazyZ",
            "v25:0","o1","f181:"+BASE+".lazyParentSupplierLjava/util/function/Supplier;",
            "v25:0","o1","f181:"+BASE+".lazyData"+COMPOUND_TAG,
            "v25:0","o1","f181:"+BASE+".registryAccess"+HOLDER_LOOKUP,
            "v25:0","o3","f181:"+BASE+".initializedZ",
            "v25:0","v25:1","f181:"+BASE+".baseClassLjava/lang/Class;",
            "v25:0","f178:"+BASE+".SUPPORTS_LAZY_CAPABILITIESZ","o153","v21:2","o153","o4","o167","o3",
            "f181:"+BASE+".isLazyZ","o177");
    /**
     * <b>The load-bearing one.</b> The event key is {@code baseClass}, and the object it carries is
     * {@code getProvider()} — which {@code AsField} overrides to return {@code owner}. That is the entire reason one
     * provider class can serve three roots on this line, so the certificate refuses a carrier whose gather no longer
     * reads that field.
     */
    private static final List<String> CARRIER_GATHER=List.of(
            "v25:0","v25:0","f180:"+BASE+".baseClassLjava/lang/Class;","v25:0",
            "m182:"+BASE+".getProvider()"+PROVIDER_IMPL_DESC,"v25:1",
            "m184:net/minecraftforge/event/ForgeEventFactory.gatherCapabilities(Ljava/lang/Class;"
                    +"Lnet/minecraftforge/common/capabilities/ICapabilityProvider;"
                    +"Lnet/minecraftforge/common/capabilities/ICapabilityProvider;)"+DISPATCHER,
            "f181:"+BASE+".capabilities"+DISPATCHER,"v25:0","o4","f181:"+BASE+".initializedZ","o177");
    private static final String CREATE_SIGNATURE="create("+ForgeCapabilityCompositionTransformer.AS_FIELD_DESC+")"
            +ForgeCapabilityCompositionTransformer.AS_FIELD_DESC;
    private static final List<String> AS_FIELD_FIELDS=List.of("18:owner:"+PROVIDER_IMPL_DESC);
    private static final List<String> AS_FIELD_CTOR=List.of("v25:0","v25:1","m183:"+BASE+".<init>(Ljava/lang/Class;)V",
            "v25:0","v25:2","f181:"+AS_FIELD+".owner"+PROVIDER_IMPL_DESC,"o177");
    /** {@code initInternal()} is the only public door onto the eager gather, and its body is that gather. */
    private static final List<String> AS_FIELD_INIT=List.of("v25:0","m182:"+AS_FIELD+".gatherCapabilities()V","o177");
    private static final List<String> AS_FIELD_SERIALIZE=List.of("v25:0","v25:1",
            "m182:"+AS_FIELD+".serializeCaps("+HOLDER_LOOKUP+")"+COMPOUND_TAG,"o176");
    private static final List<String> AS_FIELD_DESERIALIZE=List.of("v25:0","v25:1","v25:2",
            "m182:"+AS_FIELD+".deserializeCaps("+HOLDER_LOOKUP+COMPOUND_TAG+")V","o177");
    private static final String COMPOSED=RUNTIME+"$Composed";
    private static final List<String> COMPOSED_CTOR=List.of("v25:0","v25:1","v25:2",
            "t192:"+ForgeCapabilityCompositionTransformer.PROVIDER_IMPL,
            "m183:"+AS_FIELD+".<init>(Ljava/lang/Class;"+PROVIDER_IMPL_DESC+")V","o177");
    private static final List<String> COMPOSED_DISPATCHER=List.of("v25:0",
            "m182:"+COMPOSED+".getCapabilities()"+DISPATCHER,"o176");
    private static final List<String> CREATE=List.of("v25:0","m182:"+AS_FIELD+".initInternal()V","v25:0","o176");

    record Certificate(String owner,String retained,String removed,Map<String,String> methods,Map<String,List<List<String>>> seams,
            String accessorHelper) {
        boolean proves(AncestorComposition.Requirement r,byte[] bytes,Function<String,byte[]> reader){
            if(!owner.equals(r.owner())||!retained.equals(r.retainedSuperclass())||!removed.equals(r.sourceSuperclass()))return false;
            ClassNode node=parse(bytes);if(node==null||!owner.equals(node.name)||!retained.equals(node.superName)
                    ||!node.interfaces.contains(ForgeCapabilityCompositionTransformer.PROVIDER_IMPL))return false;
            List<FieldNode> fields=node.fields.stream().filter(f->f.name.equals(ForgeCapabilityCompositionTransformer.FIELD)).toList();
            if(fields.size()!=1||!fields.getFirst().desc.equals(ForgeCapabilityCompositionTransformer.AS_FIELD_DESC)
                    ||(fields.getFirst().access&(ACC_STATIC|ACC_FINAL))!=0)return false;
            if(accessorHelper!=null&&!callsHelper(node,accessorHelper))return declined("final accessor does not reach "+accessorHelper);
            for(var expected:methods.entrySet()){
                MethodNode method=method(node,expected.getKey());
                if(method==null||!expected.getValue().equals(method.access+":"+MixinInstructionFingerprint.hash(method)))return declined("final delegate changed: "+expected.getKey());
            }
            for(var expected:seams.entrySet()){
                MethodNode method=method(node,expected.getKey());if(method==null)return false;List<String> code=tokens(method);
                for(List<String> needle:expected.getValue())if(occurrences(code,needle)!=1)return declined("final lifecycle seam changed: "+expected.getKey()+" "+needle);
            }
            return protocol(removed,owner,reader);
        }
        /** SELF shape: the root's own accessor must be the one that builds this root's delegate. */
        private boolean callsHelper(ClassNode node,String helper){
            MethodNode accessor=method(node,ACCESSOR_SIGNATURE);
            if(accessor==null)return false;
            for(AbstractInsnNode instruction:accessor.instructions)if(instruction instanceof MethodInsnNode call
                    &&call.owner.equals(RUNTIME)&&call.name.equals(helper))return true;
            return false;
        }
        private boolean declined(String reason){net.forbric.kernel.util.ForbricLog.debug("[Forbric/Capabilities] %s composition proof declined: %s",owner,reason);return false;}
    }
    static Certificate certificate(ClassNode before,ClassNode after,ClassNode source,Function<String,byte[]> reader,boolean dispatch){
        if(source==null||before==null||after==null||source.superName.equals(after.superName)||!protocol(source.superName,source.name,reader))return null;
        Map<String,String> methods=new LinkedHashMap<>();Map<String,List<List<String>>> seams=new LinkedHashMap<>();
        for(MethodNode emitted:after.methods){
            MethodNode previous=method(before,emitted.name+emitted.desc);
            if(previous==null){methods.put(emitted.name+emitted.desc,emitted.access+":"+MixinInstructionFingerprint.hash(emitted));continue;}
            if(MixinInstructionFingerprint.hash(previous).equals(MixinInstructionFingerprint.hash(emitted)))continue;
            List<String> old=tokens(previous),now=tokens(emitted);List<List<String>> probes=new ArrayList<>();
            for(AbstractInsnNode instruction:emitted.instructions)if(instruction instanceof MethodInsnNode call
                    &&(call.owner.equals(RUNTIME)||call.owner.equals(after.name)&&(call.name.equals("forbric$caps")||call.name.equals("gatherCapabilities")||call.name.equals("invalidateCaps")||call.name.equals("reviveCaps")))){
                int at=real(emitted).indexOf(call);List<String> needle=List.copyOf(now.subList(Math.max(0,at-1),at+1));
                if(occurrences(old,needle)==0&&occurrences(now,needle)==1)probes.add(needle);
            }
            if(!probes.isEmpty())seams.put(emitted.name+emitted.desc,List.copyOf(probes));
        }
        if(methods.isEmpty())return null;
        // SELF shape only: which factory the accessor was given IS the per-root information on this line, so it is
        // certified — and with dispatch off that factory is `inert` for all three roots, so the switch is certified
        // too. 26.2 has no such link to pin (the delegate class is the key), so it stays null there.
        String accessor=BASE.equals(source.superName)
                ?(dispatch?HELPER.get(after.name):ForgeCapabilityCompositionTransformer.INERT_FACTORY):null;
        return new Certificate(after.name,after.superName,source.superName,Map.copyOf(methods),Map.copyOf(seams),accessor);
    }
    /** Restore only an actual unique, unconditional source constructor tail; delegating constructors stay unchanged. */
    static boolean restoreConstructorGather(ClassNode current,ClassNode source){
        if(source==null)return false;boolean changed=false;
        for(MethodNode ctor:current.methods){
            if(!ctor.name.equals("<init>"))continue;MethodNode original=method(source,ctor.name+ctor.desc);if(original==null)continue;
            List<AbstractInsnNode> sourceCode=real(original),live=real(ctor);
            if(sourceCode.size()<3||live.isEmpty()||!original.tryCatchBlocks.isEmpty())continue;
            int n=sourceCode.size();if(!(sourceCode.get(n-3)instanceof VarInsnNode receiver&&receiver.getOpcode()==ALOAD&&receiver.var==0)
                    ||!(sourceCode.get(n-2)instanceof MethodInsnNode gather&&gather.name.equals("gatherCapabilities")&&gather.desc.equals("()V")&&gather.owner.equals(source.name))
                    ||sourceCode.getLast().getOpcode()!=RETURN)continue;
            long returns=live.stream().filter(i->i.getOpcode()==RETURN).count();
            boolean delegates=live.stream().anyMatch(i->i instanceof MethodInsnNode call&&call.name.equals("<init>")&&call.owner.equals(current.name));
            if(returns!=1||delegates||live.getLast().getOpcode()!=RETURN||live.stream().anyMatch(i->i instanceof MethodInsnNode call&&call.name.equals("gatherCapabilities")&&call.desc.equals("()V")))continue;
            InsnList tail=new InsnList();tail.add(new VarInsnNode(ALOAD,0));tail.add(new MethodInsnNode(INVOKEVIRTUAL,current.name,"gatherCapabilities","()V",false));ctor.instructions.insertBefore(live.getLast(),tail);ctor.maxStack=Math.max(ctor.maxStack,1);changed=true;
        }
        return changed;
    }
    /**
     * Whether this carrier uses the SELF shape. Discriminated on the one member that only exists there: {@code AsField}
     * carries {@code <init>(Class, B)} on 1.21.1 and {@code <init>(B, boolean)} on 26.2. Never inferred from a version.
     */
    static boolean selfCarrier(Function<String,byte[]> reader){
        if(reader==null)return false;ClassNode asField=parse(reader.apply(AS_FIELD+".class"));
        return asField!=null&&method(asField,"<init>(Ljava/lang/Class;"+PROVIDER_IMPL_DESC+")V")!=null;
    }
    private static boolean protocol(String removed,String owner,Function<String,byte[]> reader){
        if(reader==null)return false;
        return BASE.equals(removed)?selfProvider(removed,owner,reader):nestedProvider(removed,owner,reader);
    }
    /**
     * The 1.21.1 shape. {@code removed} is the state carrier itself, so the proof is not "the removed class is this
     * root's provider" but "the carrier's state is exactly this, the delegate inherits all of it, and the Class the
     * delegate passes is the key the carrier's own gather reads".
     */
    private static boolean selfProvider(String carrier,String owner,Function<String,byte[]> reader){
        ClassNode nativeProvider=parse(reader.apply(carrier+".class")),asField=parse(reader.apply(AS_FIELD+".class"));
        if(nativeProvider==null||asField==null)return false;
        // P2: the carrier's own superclass is Object and its declared state is exactly the pinned set.
        if(!"java/lang/Object".equals(nativeProvider.superName))return false;
        if(!CARRIER_FIELDS.equals(fields(nativeProvider)))return false;
        // P7: no abstract member means no override site is being dropped (26.2's two hooks are its contract).
        if(nativeProvider.methods.stream().anyMatch(m->(m.access&ACC_ABSTRACT)!=0))return false;
        // P3: the constructors, in the order they pin the field initialisation and the eager default.
        if(!CARRIER_CTOR_CLASS.equals(tokens(method(nativeProvider,"<init>(Ljava/lang/Class;)V"))))return false;
        if(!CARRIER_CTOR_CLASS_BOOLEAN.equals(tokens(method(nativeProvider,"<init>(Ljava/lang/Class;Z)V"))))return false;
        // P5: the event key is the Class argument, read from baseClass, with getProvider() as the object.
        if(!CARRIER_GATHER.equals(tokens(method(nativeProvider,"doGatherCapabilities(Lnet/minecraftforge/common/capabilities/ICapabilityProvider;)V"))))return false;
        // P4: the delegate's superclass IS the carrier, so all of that state is inherited rather than copied, and the
        // four members the composition calls are Forge's own and still do what their names say.
        if(!carrier.equals(asField.superName)||(asField.access&ACC_ABSTRACT)!=0)return false;
        if(!AS_FIELD_FIELDS.equals(fields(asField)))return false;
        if(!AS_FIELD_CTOR.equals(tokens(method(asField,"<init>(Ljava/lang/Class;"+PROVIDER_IMPL_DESC+")V"))))return false;
        if(!AS_FIELD_INIT.equals(tokens(method(asField,"initInternal()V"))))return false;
        if(!AS_FIELD_SERIALIZE.equals(tokens(method(asField,"serializeInternal("+HOLDER_LOOKUP+")"+COMPOUND_TAG))))return false;
        if(!AS_FIELD_DESERIALIZE.equals(tokens(method(asField,"deserializeInternal("+HOLDER_LOOKUP+COMPOUND_TAG+")V"))))return false;
        // P6: this root's delegate, its chain, and the Class literal that replaces 26.2's per-root provider class.
        String kind=KIND.get(owner),helper=HELPER.get(owner);
        if(kind==null||helper==null)return false;
        ClassNode delegate=parse(reader.apply(RUNTIME+"$"+kind+".class")),composed=parse(reader.apply(COMPOSED+".class"));
        ClassNode owner_=parse(reader.apply(RUNTIME+".class"));
        if(delegate==null||composed==null||owner_==null||!delegate.superName.equals(composed.name)||!composed.superName.equals(AS_FIELD))return false;
        if(!COMPOSED_CTOR.equals(tokens(method(composed,"<init>(Ljava/lang/Class;Ljava/lang/Object;)V"))))return false;
        if(!COMPOSED_DISPATCHER.equals(tokens(method(composed,"dispatcher()"+DISPATCHER))))return false;
        if(!CREATE.equals(tokens(method(owner_,CREATE_SIGNATURE))))return false;
        List<String> delegateCtor=List.of("v25:0","c:L"+owner+";","v25:1",
                "m183:"+composed.name+".<init>(Ljava/lang/Class;Ljava/lang/Object;)V","o177");
        if(!delegateCtor.equals(tokens(method(delegate,"<init>(Ljava/lang/Object;)V"))))return false;
        // The accessor helper must build THIS root's delegate: that is the accessor differing only in a Class literal.
        if(!accessorTokens(kind).equals(tokens(method(owner_,helper+accessorSignature()))))return false;
        return true;
    }
    /** The 26.2 shape: the removed class is a per-root subclass of the carrier with no state of its own. */
    private static boolean nestedProvider(String removed,String owner,Function<String,byte[]> reader){
        ClassNode nativeProvider=parse(reader.apply(removed+".class")),asField=parse(reader.apply(AS_FIELD+".class"));
        if(nativeProvider==null||asField==null||!BASE.equals(nativeProvider.superName)||!BASE.equals(asField.superName)||!nativeProvider.fields.isEmpty())return false;
        MethodNode ctor=method(nativeProvider,"<init>()V");
        if(ctor==null||!tokens(ctor).equals(List.of("v25:0","o3","m183:"+BASE+".<init>(Z)V","o177")))return false;
        MethodNode fieldCtor=method(asField,"<init>(Lnet/minecraftforge/common/capabilities/ICapabilityProviderImpl;Z)V");
        if(fieldCtor==null||!tokens(fieldCtor).equals(List.of("v25:0","v21:2","m183:"+BASE+".<init>(Z)V","v25:0","v25:1","f181:"+AS_FIELD+".ownerLnet/minecraftforge/common/capabilities/ICapabilityProviderImpl;","o177")))return false;
        String kind=removed.substring(removed.lastIndexOf('$')+1);ClassNode delegate=parse(reader.apply(RUNTIME+"$"+kind+".class")),composed=parse(reader.apply(RUNTIME+"$Composed.class"));
        if(delegate==null||composed==null||!delegate.superName.equals(composed.name)||!composed.superName.equals(AS_FIELD))return false;
        MethodNode composedCtor=method(composed,"<init>(Ljava/lang/Object;)V");
        if(composedCtor==null||!tokens(composedCtor).equals(List.of("v25:0","v25:1","t192:net/minecraftforge/common/capabilities/ICapabilityProviderImpl","o3","m183:"+AS_FIELD+".<init>(Lnet/minecraftforge/common/capabilities/ICapabilityProviderImpl;Z)V","o177")))return false;
        for(String name:List.of("fireAttachCapabilitiesEvent","shouldFireAttachCapabilitiesEvent")){
            MethodNode nativeMethod=nonBridge(nativeProvider,name),delegateMethod=nonBridge(delegate,name);
            if(nativeMethod==null||delegateMethod==null||!nativeMethod.tryCatchBlocks.isEmpty()||!delegateMethod.tryCatchBlocks.isEmpty())return false;
            Set<String> casts=new HashSet<>(Set.of(owner));for(AbstractInsnNode i:nativeMethod.instructions)if(i instanceof TypeInsnNode type)casts.add(type.desc);
            if(!withoutCasts(nativeMethod,casts).equals(withoutCasts(delegateMethod,casts)))return false;
        }
        // No additional source state, initializer or override may be silently discarded.
        for(MethodNode m:nativeProvider.methods)if(!m.name.equals("<init>")&&!m.name.equals("fireAttachCapabilitiesEvent")&&!m.name.equals("shouldFireAttachCapabilitiesEvent"))return false;
        return true;
    }
    private static String accessorSignature(){
        String d=ForgeCapabilityCompositionTransformer.AS_FIELD_DESC;
        return "("+d+"Ljava/lang/Object;)"+d;
    }
    /** The measured body of {@code KernelForgeCapabilities.<helper>}: one branch, one delegate, one factory. */
    private static List<String> accessorTokens(String kind){
        String delegate=RUNTIME+"$"+kind;
        return List.of("v25:0","o198","v25:0","o167","t187:"+delegate,"o89","v25:1",
                "m183:"+delegate+".<init>(Ljava/lang/Object;)V",
                "m184:"+RUNTIME+".create("+ForgeCapabilityCompositionTransformer.AS_FIELD_DESC+")"+ForgeCapabilityCompositionTransformer.AS_FIELD_DESC,
                "o176");
    }
    private static MethodNode nonBridge(ClassNode node,String name){List<MethodNode> found=node.methods.stream().filter(m->m.name.equals(name)&&(m.access&ACC_BRIDGE)==0).toList();return found.size()==1?found.getFirst():null;}
    private static List<String> withoutCasts(MethodNode method,Set<String> allowed){List<String> result=new ArrayList<>();for(AbstractInsnNode i:real(method)){if(i instanceof TypeInsnNode t&&i.getOpcode()==CHECKCAST&&allowed.contains(t.desc))continue;result.add(token(i));}return result;}
    private static MethodNode method(ClassNode node,String signature){return node==null?null:node.methods.stream().filter(m->(m.name+m.desc).equals(signature)).findFirst().orElse(null);}
    private static ClassNode parse(byte[] bytes){if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
    private static List<AbstractInsnNode> real(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();}
    private static List<String> tokens(MethodNode method){return method==null?List.of("<absent>"):real(method).stream().map(ForgeCapabilityProtocol::token).toList();}
    /** {@code access:name:desc} in declaration order — the whole of a class's state, including which fields are final. */
    private static List<String> fields(ClassNode node){return node.fields.stream().map(f->f.access+":"+f.name+":"+f.desc).toList();}
    private static String token(AbstractInsnNode i){if(i instanceof VarInsnNode v)return "v"+i.getOpcode()+":"+v.var;if(i instanceof MethodInsnNode m)return "m"+i.getOpcode()+":"+m.owner+"."+m.name+m.desc;if(i instanceof FieldInsnNode f)return "f"+i.getOpcode()+":"+f.owner+"."+f.name+f.desc;if(i instanceof TypeInsnNode t)return "t"+i.getOpcode()+":"+t.desc;if(i instanceof LdcInsnNode l)return "c:"+constant(l.cst);return "o"+i.getOpcode();}
    /**
     * A loadable constant. Deliberately without the opcode: {@code ldc} and {@code ldc_w} are the same constant, and
     * which one javac emits depends on the constant-pool index, i.e. on unrelated edits to the class.
     */
    private static String constant(Object value){return value instanceof Type type?type.getDescriptor():String.valueOf(value);}
    private static int occurrences(List<String> code,List<String> needle){int count=0;for(int i=0;i+needle.size()<=code.size();i++)if(code.subList(i,i+needle.size()).equals(needle))count++;return count;}
}
