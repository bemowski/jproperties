package net.jmatrix.jproperties;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.jmatrix.jproperties.util.ClassLogFactory;

import org.apache.commons.logging.Log;

/**
 * Static methods used to substitute tokens for values.
 * 
 * Note: This class has some very tricky logic supporting 2 types of 
 * substitutions (partial and complete), nested tokens (${key.${env}}),
 * and default values.  
 * 
 * When changing this class you should: 
 * a) know what you are doing
 * b) know the desired token substitution syntax and function.
 * c) test extensively with many tokenized properties files.
 * 
 * you have been warned.  Bemo, 21 July 2009.
 */
public final class SubstitutionProcessor {
   public static Log log=ClassLogFactory.getLog();
   
   // This was the original (non-functional) regex.
   //static final String TOKEN_REGEX = "\\$\\{.*?\\}";
   // for nested token trial. This regex seems to work in parsing
   // some standard files as well as nested token replacements... 
   // i'm leaving it in place, pending further testing.  17 mar 2009.  bemo.
   static final String TOKEN_REGEX="\\$\\{[^\\$\\{\\}]+\\}";

   static final String COMPLETE_TOKEN_REGEX="^"+TOKEN_REGEX+"$";
   
   static final Pattern PARTIAL_PATTERN = Pattern.compile(TOKEN_REGEX);
   
   static final Pattern COMPLETE_PATTERN = Pattern.compile(COMPLETE_TOKEN_REGEX);

   public static int MAX_RECURSIVE_SUBSTITUTIONS=20;
   
   public static boolean debug=false;

   
   /** 
    * Public facing method.
    * 
    * There are 2 types of substitutions: partial and complete.  Complete 
    * substitutions are only possible when the value is simply the token. 
    * Partial substitutions are string only.  Complete substitutions can 
    * replace a token with an object - like EProperties or List.
    * 
    * Partial Substitution: 
    * key="This is a string with a ${token} value"
    * 
    * Complete Substitution:
    * key2=${token}
    * 
    * The result of a partial substitution is ALWAYS a String.  The result
    * of a complete substitution can be any of [ String, EProperties, List ].
    * 
    */
   public static final String processSubstitution(String s, JProperties p) {
      return (String)processSubstitution(s, p, String.class);
   }
   
   public static final boolean containsTokens(String s) {
      Matcher matcher=PARTIAL_PATTERN.matcher(s);
      return matcher.find();
   }
   
   /** */
   public static final Object processSubstitution(String s, JProperties p, 
                                                  Class returnType) {
      if (s == null || s.length() == 0)
         return s;
      return processSubstitution(s, p, returnType, 0);
   }
   
   ///////////////////////////////////////////////////////////////////////
   /////////////////////////  PRIVATE METHODS  ///////////////////////////
   ///////////////////////////////////////////////////////////////////////
   
   
   /** */
   private static final boolean isCompleteToken(String s) {
      Matcher matcher=COMPLETE_PATTERN.matcher(s);
      return matcher.matches();
   }
   
   /** */
   private static final Object processSubstitution(String s, JProperties p, 
                                                   Class returnType, int iter) {
      log.trace("processSubstitution("+iter+":"+s+"', to return type "+returnType+")");
      
      if (returnType == null)
         returnType=Object.class;
      
      if (returnType.equals(String.class)) {
         Object rval= recursiveReplace(s, p, iter);
         if (rval == null)
            return rval;
         
         if (rval instanceof String) 
            return rval;
         else {
            log.warn("processSubstitution called with String.class, "+
                  "but result is "+rval.getClass().getName());
            return s;
         }
      } else {
         if (isCompleteToken(s)) {
            return completeReplace(s, p, iter);
         } else {
            return recursiveReplace(s, p, iter);
         }
      }
   }
   
   /**
    * This is replacing a complete key - but should work the same as
    * the partial replace if the value returned by the token lookup is a
    * String.  */
   private static final Object completeReplace(String s, JProperties p, 
                                               int iter) {
      log.trace("Complete replace of token '"+s+"', iter "+iter);
      
      SubstitutionToken subToken=new SubstitutionToken(s);
      String key=subToken.getKey();
      String def=subToken.getDefault();
      
      Object val=p.internalFindValue(key);
      
      if (val == null) {
         return def;
      } else {
         if (val instanceof String) {
            String sval=(String)val;
            log.trace("Complete replace, result is string '"+sval+"'");
            if (containsTokens(sval)) {
               return processSubstitution(sval, p, Object.class, iter);
            }
         }
         return val;
      }
   }
   
   /**
    * This is replacing a partial key.
    */
   private static final Object recursiveReplace(String s, JProperties p, int iter) {
      log.trace("Recursive replace of token '"+s+"', iter "+iter);
      // the word 'recursive' in this sense is really a recursion to support
      // nested tokens.  Which is why it cannot fix the other, different
      // recursion problem noted below.
      // The recursion here is to support nested keys, like this:
      // env=test
      // foo=${env->${env}}
      // Above, 'foo' has a vaplue that is nested.  the 'recursion' of this 
      // method is to support such nesting.  In the above case, we would
      // go thru iter: 0, 1, 2 (where 2 doesn't really do anything).
      
      
      // FIXME: This class allows recursive replace - which should not
      // be allowed!!  
      // Here, recursive replace is defined as the following:
      //   key.a=${key.b}
      //   key.b=${key.a}
      // Which is valid syntax.  (and more common when we consider included
      // properties files).
      // 
      // The real solution is not the iter parameter, but a
      // ThreadLocal variable that counts the recursion.
      //
      // The key point here though is that we'd have a better error message
      // but it would still be an error.  It will error either way.  If
      // we don't put in the thread local counter, it will result in a 
      // stack overflow.
      
      if (iter > MAX_RECURSIVE_SUBSTITUTIONS)
         throw new JPRuntimeException("Error: recursive replacement limit on '"+
               s+"' "+iter+" exceeds max "+MAX_RECURSIVE_SUBSTITUTIONS);
      
      // Note: Pattern instance is immutable and thread safe.
      Matcher matcher = PARTIAL_PATTERN.matcher(s);
      
      String newVal = s;
      while (matcher.find()) {
         log.debug("replace: found tokens in '"+s+"'");
         
         // value=something ${key|default} something else
         // token will be '${key|default}
         String token = s.substring(matcher.start(), matcher.end());
         log.debug("   Token: '"+token+"', props="+p.getDebugId());
         
         SubstitutionToken subToken=new SubstitutionToken(token);
         String key=subToken.getKey();
         String def=subToken.getDefault();
         
         
         String replaceVal = p.internalFindString(key);

         if (replaceVal != null) {
            log.debug("   Found value for token "+token+": "+replaceVal);
            newVal = newVal.replace(token, replaceVal);
         } else {
            if (def != null) {
               log.debug("   No value for token "+token+", using default: "+def);
               newVal=newVal.replace(token, def);
            }
            else {
               // nothing - this will fail by design.
            }
         }
      }

      if (newVal.equals(s)) {
         log.debug("returning '"+newVal+"' at iter "+iter);
         
         if (containsTokens(newVal)) {
            log.info("Unresolvable Substitution in token "+newVal);
         }
         
         return newVal; // no change
      } else {
         return processSubstitution(newVal, p, null, iter+1);
      }
   }
   
   /** */
   static class SubstitutionToken {
      private String key=null;
      private String def=null;
      
      /** 
       * Constructor takes a complete token, in the following form: 
       * ${key} or ${key|def}
       * ie, the default value is optional.
       */
      public SubstitutionToken(String s) {
         String tokenKey = s.substring(2, s.length() - 1);
         int pipeindex=tokenKey.indexOf("|");
         if (pipeindex != -1) {
            key=tokenKey.substring(0, pipeindex);
            def=tokenKey.substring(pipeindex+1);
         } else {
            key=tokenKey;
         }
      }
      public String getKey() {
         return key;
      }
      public String getDefault() {
         return def;
      }
   }
}
