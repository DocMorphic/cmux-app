import com.ibm.icu.text.Normalizer2;import com.ibm.icu.lang.UCharacter;import java.nio.charset.StandardCharsets;import java.util.Base64;
class GenerateCases {
 static String enc(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
 static void row(String id,String text,String query){System.out.println(id+"\t"+enc(text)+"\t"+enc(query));}
 public static void main(String[]args){var nfd=Normalizer2.getNFDInstance();
  for(int cp=0;cp<=0x10ffff;cp++)if(Character.isValidCodePoint(cp)&&!(cp>=0xd800&&cp<=0xdfff)){
   String s=new String(Character.toChars(cp)),f=UCharacter.foldCase(s,true),id=String.format("u%06x",cp);
   if(!s.equals(f)){
    row("fold-"+id,s,f);
    if(f.codePointCount(0,f.length())>1){int first=f.offsetByCodePoints(0,1);row("fold-prefix-"+id,s,f.substring(0,first));row("fold-suffix-"+id,s,f.substring(first));}
   }
   if(cp<0x10000&&!(cp>=0xac00&&cp<=0xd7a3)){
    String d=nfd.normalize(s);if(!s.equals(d)){row("canonical-forward-"+id,s,d);row("canonical-reverse-"+id,d,s);}
   }
  }
 }
}
