package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** SDK JSONSerialization projection from response bytes, independent of Android JSONTokener. */
internal object NativeAuthJson {
    /** JSONObject cannot store nonfinite java.lang.Number; typed SDK fields must not see a String/Bool instead. */
    private data class NonFiniteNumber(val value:Double)
    fun objectValue(bytes:ByteArray):JSONObject {
        val text=decode(bytes)
        return Parser(text).objectValue()
    }
    private fun decode(bytes:ByteArray):String {
        fun starts(vararg values:Int)=bytes.size>=values.size && values.indices.all{(bytes[it].toInt() and 255)==values[it]}
        fun at(index:Int)=bytes.getOrNull(index)?.toInt()?.and(255)
        val encoding:String;val offset:Int;val unit:Int
        when {
            starts(0xef,0xbb,0xbf)->{encoding="UTF-8";offset=3;unit=1}
            // Foundation recognizes this prefix as UTF-16LE, including UTF-32LE's BOM.
            starts(0xff,0xfe)->{encoding="UTF-16LE";offset=2;unit=2}
            starts(0xfe,0xff)->{encoding="UTF-16BE";offset=2;unit=2}
            starts(0,0,0xfe,0xff)->{encoding="UTF-32BE";offset=4;unit=4}
            bytes.size>=4 && at(0)==0 && at(1)==0 && at(2)==0->{encoding="UTF-32BE";offset=0;unit=4}
            bytes.size>=4 && at(1)==0 && at(2)==0 && at(3)==0->{encoding="UTF-32LE";offset=0;unit=4}
            bytes.size>=4 && at(0)==0 && at(2)==0->{encoding="UTF-16BE";offset=0;unit=2}
            bytes.size>=4 && at(1)==0 && at(3)==0->{encoding="UTF-16LE";offset=0;unit=2}
            else->{encoding="UTF-8";offset=0;unit=1}
        }
        // Foundation drops an incomplete final UTF-16/32 code unit, not malformed full units.
        val length=(bytes.size-offset)/unit*unit
        return Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,length)).toString()
    }
    private class Parser(private val text:String) {
        private var index=0
        private fun invalid():Nothing=throw IllegalArgumentException("Invalid account JSON")
        private fun requireValue(value:Boolean){if(!value)invalid()}
        private fun whitespace(){while(index<text.length && text[index] in " \t\r\n")index++}
        private fun take(c:Char):Boolean {whitespace();if(index>=text.length || text[index]!=c)return false;index++;return true}
        fun objectValue():JSONObject {
            val value=value(0);whitespace();requireValue(index==text.length)
            return value as? JSONObject ?: invalid()
        }
        private fun value(depth:Int):Any {
            requireValue(depth<=512);whitespace();requireValue(index<text.length)
            return when(text[index]) {
                '{'->objectBody(depth)
                '['->arrayBody(depth)
                '"'->string()
                't'->{literal("true");true}
                'f'->{literal("false");false}
                'n'->{literal("null");JSONObject.NULL}
                '-',in '0'..'9'->number()
                else->invalid()
            }
        }
        private fun objectBody(depth:Int):JSONObject {
            index++;val result=JSONObject();if(take('}'))return result
            while(true) {
                val key=string();requireValue(take(':'));val next=value(depth+1)
                if(!result.has(key))result.put(key,next) // First duplicate wins; later values still validate.
                if(take('}'))return result
                requireValue(take(','));if(take('}'))return result
            }
        }
        private fun arrayBody(depth:Int):JSONArray {
            index++;val result=JSONArray();if(take(']'))return result
            while(true) {
                result.put(value(depth+1));if(take(']'))return result
                requireValue(take(','));if(take(']'))return result
            }
        }
        private fun literal(value:String){requireValue(text.startsWith(value,index));index+=value.length}
        private fun hexUnit():Char {
            requireValue(index+4<=text.length);var value=0
            repeat(4){val digit=when(val c=text[index++]) {
                in '0'..'9'->c-'0';in 'a'..'f'->c-'a'+10;in 'A'..'F'->c-'A'+10;else->invalid()
            };value=value*16+digit}
            return value.toChar()
        }
        private fun string():String {
            requireValue(take('"'));val value=StringBuilder()
            while(index<text.length) {
                val c=text[index++]
                if(c=='"')return value.toString()
                requireValue(c.code>=32)
                if(c=='\\') {
                    requireValue(index<text.length)
                    when(val escape=text[index++]) {
                        '"','\\','/'->value.append(escape)
                        'b'->value.append('\b');'f'->value.append('\u000c');'n'->value.append('\n');'r'->value.append('\r');'t'->value.append('\t')
                        'u'->{val unit=hexUnit()
                            when {
                                unit.isHighSurrogate()->{requireValue(index+2<=text.length && text[index]=='\\' && text[index+1]=='u');index+=2
                                    val low=hexUnit();requireValue(low.isLowSurrogate());value.append(unit).append(low)}
                                unit.isLowSurrogate()->invalid()
                                else->value.append(unit)
                            }
                        }
                        else->invalid()
                    }
                }else value.append(c)
            }
            invalid()
        }
        private fun number():Any {
            val start=index;if(text[index]=='-')index++
            requireValue(index<text.length)
            if(text[index]=='0')index++ else {
                requireValue(text[index] in '1'..'9');while(index<text.length && text[index] in '0'..'9')index++
            }
            if(index<text.length && text[index]=='.') {
                index++;val before=index;while(index<text.length && text[index] in '0'..'9')index++
                requireValue(index>before)
            }
            val mantissaEnd=index;var exponent=0
            if(index<text.length && text[index] in "eE") {
                index++;val before=index;if(index<text.length && text[index] in "+-")index++
                val digits=index;while(index<text.length && text[index] in '0'..'9')index++
                requireValue(index>digits && index-before<=4);exponent=text.substring(before,index).toInt()
            }
            val token=text.substring(start,index);val mantissa=text.substring(start,mantissaEnd)
            val unsigned=mantissa.removePrefix("-")
            val digits=mantissa.count{it in '0'..'9'}-if(unsigned=="0" || unsigned.startsWith("0."))1 else 0
            val result=if(digits>17)decimal(mantissa,exponent) else token.toDoubleOrNull() ?: invalid()
            // The exercised Darwin reader rejects positive overflow but accepts negative
            // Double overflow. Neither accepted numeric form casts to a SDK String or Bool.
            requireValue(result<=Double.MAX_VALUE)
            return if(result.isFinite())result else NonFiniteNumber(result)
        }
        private fun decimal(mantissa:String,explicitExponent:Int):Double {
            var coefficient=BigInteger.ZERO;var fraction=false;var overflow=false;var exponent=explicitExponent
            for(c in mantissa)when(c) {
                '.'->fraction=true
                in '0'..'9'->{val next=coefficient.multiply(BigInteger.TEN).add(BigInteger.valueOf((c-'0').toLong()))
                    if(!overflow && next<=MAX_COEFFICIENT){coefficient=next;if(fraction)exponent--}
                    else {overflow=true;if(!fraction)exponent++}
                }
            }
            requireValue(exponent in -128..127)
            // NSDecimalNumber converts its coefficient first, then scales one decimal
            // place at a time. A correctly rounded BigDecimal conversion differs at ties.
            var value=coefficient.toDouble()
            if(exponent<0)repeat(-exponent){value/=10.0}else repeat(exponent){value*=10.0}
            return if(mantissa.startsWith('-'))-value else value
        }
    }
    private val MAX_COEFFICIENT=BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
}
